"""
Satori OCR Microservice — Dual-Language Edition (v2.0)
FastAPI + PaddleOCR — supports Japanese (hiragana, katakana, kanji) AND Vietnamese.

Two PaddleOCR models are loaded at startup:
  1. lang=japan  — optimized for Japanese text (CJK + kana)
  2. lang=vi     — optimized for Vietnamese text (Latin + diacritics)

For each image, both models run OCR. Results are merged line-by-line:
  - Lines with CJK characters → Japanese model wins
  - Lines with Vietnamese diacritics → Vietnamese model wins
  - Pure Latin/numbers → higher confidence wins

Endpoints:
  POST /ocr   { "image": "<base64 PNG/JPG>" }
            → { "text": "...", "confidence": 0.97, "line_count": 42, "processing_ms": 380 }
  GET  /health → { "status": "ok" | "initialising", "models": ["japan", "vi"] }
"""

import base64
import io
import logging
import time
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from PIL import Image

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

# ── Global OCR instances ─────────────────────────────────────────────────────
_ocr_ja = None
_ocr_vi = None
_initialised = False

# ── Unicode range helpers ────────────────────────────────────────────────────

_CJK_RANGES = (
    (0x3040, 0x309F),  # Hiragana
    (0x30A0, 0x30FF),  # Katakana
    (0x4E00, 0x9FFF),  # CJK Unified Ideographs
    (0x3400, 0x4DBF),  # CJK Extension A
    (0xFF66, 0xFF9F),  # Halfwidth Katakana
)

_VIETNAMESE_DIACRITICS = set(
    "àáảãạăắằẳẵặâấầẩẫậèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợ"
    "ùúủũụưứừửữựỳýỷỹỵđ"
    "ÀÁẢÃẠĂẮẰẲẴẶÂẤẦẨẪẬÈÉẺẼẸÊẾỀỂỄỆÌÍỈĨỊÒÓỎÕỌÔỐỒỔỖỘƠỚỜỞỠỢ"
    "ÙÚỦŨỤƯỨỪỬỮỰỲÝỶỸỴĐ"
)


def _contains_cjk(text: str) -> bool:
    """Check if text contains any CJK characters (kanji, hiragana, katakana)."""
    for ch in text:
        cp = ord(ch)
        for lo, hi in _CJK_RANGES:
            if lo <= cp <= hi:
                return True
    return False


def _contains_vietnamese_diacritics(text: str) -> bool:
    """Check if text contains Vietnamese-specific diacritics (ắ, ơ, ể, ứ, ộ, đ, etc.)."""
    return bool(_VIETNAMESE_DIACRITICS & set(text))


def _y_center(bbox) -> float:
    """Get Y-center of a bounding box [[x1,y1],[x2,y2],[x3,y3],[x4,y4]]."""
    return (bbox[0][1] + bbox[2][1]) / 2.0


def _y_height(bbox) -> float:
    """Get approximate height of a bounding box."""
    return abs(bbox[2][1] - bbox[0][1])


def _extract_lines(result):
    """Extract lines from PaddleOCR result as list of (bbox, text, confidence)."""
    lines = []
    if result and result[0]:
        for block in result:
            if block is None:
                continue
            for line in block:
                bbox = line[0]
                text_val = line[1][0].strip()
                conf_val = float(line[1][1])
                if text_val:
                    lines.append((bbox, text_val, conf_val))
    lines.sort(key=lambda x: _y_center(x[0]))
    return lines


def _merge_dual_results(ja_lines, vi_lines):
    """
    Merge results from Japanese and Vietnamese OCR models.

    Strategy:
    1. Match lines from both models by Y-coordinate overlap
    2. For matched pairs, pick based on content:
       - CJK characters present → Japanese model result
       - Vietnamese diacritics present → Vietnamese model result
       - Otherwise → higher confidence wins
    3. Unmatched lines are included and sorted into correct Y-position
    """
    merged = []  # (y_center, text, confidence)
    used_vi = set()

    for ja_bbox, ja_text, ja_conf in ja_lines:
        ja_yc = _y_center(ja_bbox)
        ja_h = max(_y_height(ja_bbox), 1.0)

        best_vi_idx = None
        best_distance = float("inf")

        for vi_idx, (vi_bbox, vi_text, vi_conf) in enumerate(vi_lines):
            if vi_idx in used_vi:
                continue
            vi_yc = _y_center(vi_bbox)
            vi_h = max(_y_height(vi_bbox), 1.0)

            threshold = (ja_h + vi_h) / 3.0
            distance = abs(ja_yc - vi_yc)

            if distance < threshold and distance < best_distance:
                best_distance = distance
                best_vi_idx = vi_idx

        if best_vi_idx is not None:
            used_vi.add(best_vi_idx)
            _, vi_text, vi_conf = vi_lines[best_vi_idx]

            ja_has_cjk = _contains_cjk(ja_text)
            vi_has_vn = _contains_vietnamese_diacritics(vi_text)

            if ja_has_cjk and not vi_has_vn:
                merged.append((ja_yc, ja_text, ja_conf))
            elif vi_has_vn and not ja_has_cjk:
                merged.append((ja_yc, vi_text, vi_conf))
            else:
                # Both or neither — pick higher confidence
                if ja_conf >= vi_conf:
                    merged.append((ja_yc, ja_text, ja_conf))
                else:
                    merged.append((ja_yc, vi_text, vi_conf))
        else:
            merged.append((ja_yc, ja_text, ja_conf))

    # Add unmatched Vietnamese-only lines
    for vi_idx, (vi_bbox, vi_text, vi_conf) in enumerate(vi_lines):
        if vi_idx not in used_vi:
            merged.append((_y_center(vi_bbox), vi_text, vi_conf))

    # Sort by Y-position to maintain top-to-bottom reading order
    merged.sort(key=lambda x: x[0])
    return [(text, conf) for _, text, conf in merged]


# ── Model initialisation ─────────────────────────────────────────────────────

def _init_ocr():
    """Initialise both PaddleOCR models. Called once from the lifespan handler."""
    global _ocr_ja, _ocr_vi, _initialised
    from paddleocr import PaddleOCR

    logger.info("Initialising PaddleOCR dual-model (japan + vi) — downloads may take a few minutes…")
    t0 = time.time()

    _ocr_ja = PaddleOCR(
        lang="japan",
        use_angle_cls=True,
        show_log=False,
        use_gpu=False,
    )
    logger.info("Japanese model ready in %.1fs", time.time() - t0)

    t1 = time.time()
    _ocr_vi = PaddleOCR(
        lang="vi",
        use_angle_cls=True,
        show_log=False,
        use_gpu=False,
    )
    logger.info("Vietnamese model ready in %.1fs", time.time() - t1)

    _initialised = True
    logger.info("Both OCR models ready in %.1fs total", time.time() - t0)


# ── FastAPI lifespan (startup / shutdown) ────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    import asyncio
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, _init_ocr)
    yield


app = FastAPI(title="Satori OCR Service", version="2.0.0", lifespan=lifespan)


# ── DTOs ────────────────────────────────────────────────────────────────────

class OcrRequest(BaseModel):
    image: str  # base64-encoded PNG or JPG


class OcrResponse(BaseModel):
    text: str
    confidence: float
    line_count: int
    processing_ms: int


# ── Endpoints ────────────────────────────────────────────────────────────────

@app.post("/ocr", response_model=OcrResponse)
async def extract_text(request: OcrRequest):
    if not _initialised:
        raise HTTPException(
            status_code=503,
            detail="OCR engine is still initialising — retry in a moment.",
        )

    t0 = time.time()

    try:
        raw = base64.b64decode(request.image)
        img = Image.open(io.BytesIO(raw)).convert("RGB")
        img_array = np.array(img)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Invalid image data: {exc}")

    img_w, img_h = img.size
    logger.debug("Image dimensions: %dx%d", img_w, img_h)

    # Run both models — each may fail independently
    result_ja = None
    result_vi = None
    ja_error = None
    vi_error = None

    try:
        result_ja = _ocr_ja.ocr(img_array, cls=True)
    except Exception as exc:
        ja_error = str(exc) or type(exc).__name__
        logger.exception("Japanese OCR engine error on %dx%d image", img_w, img_h)

    try:
        result_vi = _ocr_vi.ocr(img_array, cls=True)
    except Exception as exc:
        vi_error = str(exc) or type(exc).__name__
        logger.exception("Vietnamese OCR engine error on %dx%d image", img_w, img_h)

    if result_ja is None and result_vi is None:
        errors = []
        if ja_error:
            errors.append(f"ja: {ja_error}")
        if vi_error:
            errors.append(f"vi: {vi_error}")
        detail = f"Both OCR engines failed on {img_w}x{img_h} image"
        if errors:
            detail += f" — {'; '.join(errors)}"
        raise HTTPException(status_code=500, detail=detail)

    ja_lines = _extract_lines(result_ja) if result_ja else []
    vi_lines = _extract_lines(result_vi) if result_vi else []

    # Merge or fall back to single model
    if ja_lines and vi_lines:
        merged = _merge_dual_results(ja_lines, vi_lines)
    elif ja_lines:
        merged = [(text, conf) for _, text, conf in ja_lines]
    elif vi_lines:
        merged = [(text, conf) for _, text, conf in vi_lines]
    else:
        # Both models returned results but extracted zero lines
        elapsed_ms = int((time.time() - t0) * 1000)
        logger.info("OCR: 0 lines from %dx%d image, %dms", img_w, img_h, elapsed_ms)
        return OcrResponse(text="", confidence=0.0, line_count=0, processing_ms=elapsed_ms)

    texts = [t for t, _ in merged]
    confidences = [c for _, c in merged]
    avg_conf = sum(confidences) / len(confidences) if confidences else 0.0
    elapsed_ms = int((time.time() - t0) * 1000)

    logger.info(
        "OCR: %d lines (ja=%d, vi=%d), conf=%.3f, %dms",
        len(texts), len(ja_lines), len(vi_lines), avg_conf, elapsed_ms,
    )

    return OcrResponse(
        text="\n".join(texts),
        confidence=round(avg_conf, 4),
        line_count=len(texts),
        processing_ms=elapsed_ms,
    )


@app.get("/health")
async def health():
    return {
        "status": "ok" if _initialised else "initialising",
        "models": ["japan", "vi"],
    }
