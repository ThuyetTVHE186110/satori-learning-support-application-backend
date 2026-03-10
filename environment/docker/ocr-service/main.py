"""
Satori OCR Microservice — Pro Edition (v4.0)
FastAPI + PaddleOCR — Multi-stage pipeline for Minna no Nihongo textbooks.

Upgrade summary over v3.0
─────────────────────────
4. Vocabulary table detection (new)
   MnN vocabulary lists are printed as fixed-column tables:
     No. │ Japanese word │ Reading    │ Vietnamese meaning │ Example
   After furigana merging, lines are clustered by Y-coordinate into rows
   (25px bucket tolerance). A row is a candidate table row when:
     • It contains ≥ 3 cells (columns) after splitting on whitespace runs
     • At least one cell contains CJK / kana (ensures it's a vocab row)
   Detected table rows are returned as tab-separated strings in the
   new `table_rows` field of the response — the Java side's MnNTableParser
   converts these directly to ExtractedVocabulary objects without an LLM call.

Previous upgrades (v2–v3):
─────────────────────────
1. Image pre-processing: grayscale → CLAHE → adaptive binarization → deskew
2. Furigana bounding-box detection: merged into 漢字[ふりがな] notation
3. Dual-model OCR: PaddleOCR japan + vi models with Y-position merge

Endpoints:
  POST /ocr   { "image": "<base64 PNG/JPG>" }
            → { "text": "...", "rich_text": "...", "confidence": 0.97,
                "line_count": 42, "furigana_merged": 5, "processing_ms": 380,
                "table_rows": ["1\\t行きます\\tいきます\\t(đt) đi\\t...", ...] }
  GET  /health → { "status": "ok" | "initialising", "models": ["japan", "vi"] }
"""

import base64
import io
import logging
import time
from contextlib import asynccontextmanager

import cv2
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

_HIRAGANA_RANGE = (0x3040, 0x309F)
_KATAKANA_RANGE = (0x30A0, 0x30FF)

_VIETNAMESE_DIACRITICS = set(
    "àáảãạăắằẳẵặâấầẩẫậèéẻẽẹêếềểễệìíỉĩịòóỏõọôốồổỗộơớờởỡợ"
    "ùúủũụưứừửữựỳýỷỹỵđ"
    "ÀÁẢÃẠĂẮẰẲẴẶÂẤẦẨẪẬÈÉẺẼẸÊẾỀỂỄỆÌÍỈĨỊÒÓỎÕỌÔỐỒỔỖỘƠỚỜỞỠỢ"
    "ÙÚỦŨỤƯỨỪỬỮỰỲÝỶỸỴĐ"
)


def _contains_cjk(text: str) -> bool:
    for ch in text:
        cp = ord(ch)
        for lo, hi in _CJK_RANGES:
            if lo <= cp <= hi:
                return True
    return False


def _is_pure_kana(text: str) -> bool:
    """Return True if text contains ONLY hiragana, katakana, prolonged-sound mark, or spaces."""
    stripped = text.strip()
    if not stripped:
        return False
    for ch in stripped:
        if ch in (' ', '　', 'ー', '・'):
            continue
        cp = ord(ch)
        if not (_HIRAGANA_RANGE[0] <= cp <= _HIRAGANA_RANGE[1] or
                _KATAKANA_RANGE[0] <= cp <= _KATAKANA_RANGE[1]):
            return False
    return True


def _contains_vietnamese_diacritics(text: str) -> bool:
    return bool(_VIETNAMESE_DIACRITICS & set(text))


def _y_center(bbox) -> float:
    return (bbox[0][1] + bbox[2][1]) / 2.0


def _y_height(bbox) -> float:
    return abs(bbox[2][1] - bbox[0][1])


def _x_center(bbox) -> float:
    return (bbox[0][0] + bbox[2][0]) / 2.0


# ── Image Pre-processing Pipeline ────────────────────────────────────────────

def _preprocess_for_ocr(img_array: np.ndarray) -> np.ndarray:
    """
    Multi-stage image pre-processing optimized for Japanese/Vietnamese textbook pages.

    Stages
    ──────
    1. Grayscale          — reduces colour noise; kana strokes are monochromatic
    2. CLAHE              — adaptive contrast; handles shadow gradients from book gutter
    3. Adaptive threshold — Sauvola-style local binarization (Gaussian window, C=10)
                            Preserves small Furigana strokes that Otsu loses on bright pages
    4. Deskew             — corrects ±15° page rotation using contour minAreaRect
    5. Back to RGB        — PaddleOCR expects 3-channel input

    Parameters tuned for A4 page scanned at 200-300 DPI.
    """
    # ── 1. Grayscale ─────────────────────────────────────────────────────────
    gray = cv2.cvtColor(img_array, cv2.COLOR_RGB2GRAY)

    # ── 2. CLAHE (Contrast Limited Adaptive Histogram Equalization) ──────────
    # tileGridSize=(8,8) divides page into 8×8 tiles — good for A4 at 200 DPI
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    enhanced = clahe.apply(gray)

    # ── 3. Adaptive binarization (Gaussian local threshold) ──────────────────
    # blockSize=31 captures local area (~4mm at 200 DPI) — wide enough for
    # kanji strokes but not too wide to average out furigana.
    # C=10 shifts threshold down to keep thin strokes (furigana) as foreground.
    binary = cv2.adaptiveThreshold(
        enhanced,
        maxValue=255,
        adaptiveMethod=cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        thresholdType=cv2.THRESH_BINARY,
        blockSize=31,
        C=10,
    )

    # ── 4. Deskew ─────────────────────────────────────────────────────────────
    binary = _deskew(binary)

    # ── 5. Convert back to 3-channel RGB for PaddleOCR ───────────────────────
    return cv2.cvtColor(binary, cv2.COLOR_GRAY2RGB)


def _deskew(binary: np.ndarray) -> np.ndarray:
    """
    Detect and correct page skew using minAreaRect on large text contours.

    - Finds all contours with significant area (text blocks, not noise)
    - Computes the dominant rotation angle via minAreaRect
    - Applies warpAffine to straighten the image
    - Clamps correction to ±15° to avoid false positives on heavily tilted images

    Returns the deskewed binary image (unchanged if no significant skew detected).
    """
    # Find text-sized contours (filter out tiny noise specks)
    contours, _ = cv2.findContours(
        cv2.bitwise_not(binary),   # invert: text = white for findContours
        cv2.RETR_EXTERNAL,
        cv2.CHAIN_APPROX_SIMPLE,
    )

    h, w = binary.shape
    min_area = (h * w) * 0.0003  # at least 0.03% of page area

    angles = []
    for cnt in contours:
        area = cv2.contourArea(cnt)
        if area < min_area:
            continue
        rect = cv2.minAreaRect(cnt)
        angle = rect[2]
        # minAreaRect returns angles in [-90, 0); map to (-45, 45]
        if angle < -45:
            angle += 90
        angles.append(angle)

    if not angles:
        return binary

    # Median angle is more robust than mean (ignores outlier contours)
    angles.sort()
    median_angle = angles[len(angles) // 2]

    if abs(median_angle) < 0.5:
        return binary  # negligible skew — skip warpAffine overhead

    if abs(median_angle) > 15:
        logger.warning("Skew angle %.1f° exceeds ±15° safety limit — skipping deskew", median_angle)
        return binary

    logger.debug("Deskewing by %.2f°", median_angle)
    M = cv2.getRotationMatrix2D(center=(w / 2, h / 2), angle=median_angle, scale=1.0)
    deskewed = cv2.warpAffine(
        binary, M, (w, h),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=255,   # white background
    )
    return deskewed


# ── OCR Line Extraction ───────────────────────────────────────────────────────

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


# ── Furigana Detection and Merging ────────────────────────────────────────────

def _merge_furigana(lines: list[tuple]) -> tuple[list[tuple], int]:
    """
    Detect furigana lines (small, pure-kana) and attach them to the kanji line below.

    Detection criteria for a furigana candidate:
    - Height ≤ 55% of the median text-line height
    - Text is pure hiragana / katakana (no kanji, no Latin)
    - Positioned directly above a CJK-containing line (Y-distance < 2.5× its height)

    Merge strategy:
    When a furigana line F is directly above a CJK line K, and F is shorter than K
    (meaning K likely has more characters than F's reading covers), we still attach
    F as a whole-line reading in bracket notation:
        「今日学校に行きます」 + furigana line「きょうがっこう」
        → 「今日学校に行きます[きょうがっこう]」
    This is a conservative heuristic — the LLM extraction will sort out precise
    word-level readings from its internal knowledge.

    Parameters
    ----------
    lines : list of (bbox, text, confidence)

    Returns
    -------
    merged_lines : list of (text, confidence) — furigana merged into main text
    furigana_count : int — number of furigana lines merged
    """
    if not lines:
        return [], 0

    heights = [_y_height(bbox) for bbox, _, _ in lines]
    heights_sorted = sorted(heights)
    median_h = heights_sorted[len(heights_sorted) // 2] if heights_sorted else 1.0

    furigana_h_threshold = median_h * 0.55   # smaller than 55% of median → furigana candidate
    proximity_y_limit    = median_h * 2.5    # must be within 2.5 median-heights of main text

    merged_count = 0
    consumed = set()   # indices of furigana lines absorbed into a main line

    result: list[tuple[str, float]] = []

    for i, (bbox, text, conf) in enumerate(lines):
        if i in consumed:
            continue

        h = _y_height(bbox)
        y_bot = max(pt[1] for pt in bbox)  # bottom edge of this line

        # ── Is the NEXT line a furigana line for this one? ──────────────────
        # (furigana sometimes appears BELOW the reading if OCR mis-orders)
        furigana_below_idx = None
        if i + 1 < len(lines) and (i + 1) not in consumed:
            nb_bbox, nb_text, nb_conf = lines[i + 1]
            nb_h = _y_height(nb_bbox)
            nb_y_top = min(pt[1] for pt in nb_bbox)

            if (_is_pure_kana(nb_text) and
                    nb_h <= furigana_h_threshold and
                    _contains_cjk(text) and
                    (nb_y_top - y_bot) < proximity_y_limit):
                furigana_below_idx = i + 1

        # ── Is the PREVIOUS line a furigana line for this one? ───────────────
        # (furigana appears ABOVE kanji in the physical book)
        furigana_above_idx = None
        if i > 0 and (i - 1) not in consumed:
            pa_bbox, pa_text, pa_conf = lines[i - 1]
            pa_h = _y_height(pa_bbox)
            pa_y_bot = max(pt[1] for pt in pa_bbox)
            curr_y_top = min(pt[1] for pt in bbox)

            if (_is_pure_kana(pa_text) and
                    pa_h <= furigana_h_threshold and
                    _contains_cjk(text) and
                    (curr_y_top - pa_y_bot) < proximity_y_limit):
                furigana_above_idx = i - 1

        # ── Decide merge direction (prefer "above" since that's physical reality) ──
        furigana_idx = furigana_above_idx if furigana_above_idx is not None else furigana_below_idx

        if furigana_idx is not None:
            _, furi_text, furi_conf = lines[furigana_idx]
            furi_clean = furi_text.strip()
            merged_text = text + "[" + furi_clean + "]"
            result.append((merged_text, min(conf, furi_conf)))
            consumed.add(furigana_idx)
            merged_count += 1
        else:
            result.append((text, conf))

    return result, merged_count


# ── Dual-model Result Merge ───────────────────────────────────────────────────

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

    merged.sort(key=lambda x: x[0])
    return [(text, conf) for _, text, conf in merged]


# ── Model initialisation ─────────────────────────────────────────────────────

def _init_ocr():
    global _ocr_ja, _ocr_vi, _initialised
    from paddleocr import PaddleOCR

    logger.info("Initialising PaddleOCR dual-model (japan + vi)…")
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


# ── FastAPI lifespan ─────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    import asyncio
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, _init_ocr)
    yield


app = FastAPI(title="Satori OCR Service", version="4.0.0", lifespan=lifespan)


# ── DTOs ─────────────────────────────────────────────────────────────────────

class OcrRequest(BaseModel):
    image: str  # base64-encoded PNG or JPG


class OcrResponse(BaseModel):
    text: str
    """Plain text output with furigana merged in bracket notation (漢字[ふりがな])."""

    rich_text: str
    """Identical to text for now; reserved for future HTML Ruby-tag output."""

    confidence: float
    line_count: int
    furigana_merged: int
    """Number of furigana lines detected and merged into main-text lines."""
    processing_ms: int
    version: str = "4.0.0"

    table_rows: list[str] = []
    """
    Detected vocabulary table rows as tab-separated strings.

    Each entry corresponds to one detected row in a MnN vocabulary table, e.g.:
        "1\\t行きます\\tいきます\\t(đt nhóm I) đi\\tどこへ行きますか。"

    Non-empty only when the page contains a vocabulary table structure
    (≥3-column row with at least one CJK cell). Consumed by MnNTableParser
    on the Java side to bypass LLM extraction for vocabulary chunks.
    """


# ── Endpoints ────────────────────────────────────────────────────────────────

@app.post("/ocr", response_model=OcrResponse)
async def extract_text(request: OcrRequest):
    if not _initialised:
        raise HTTPException(
            status_code=503,
            detail="OCR engine is still initialising — retry in a moment.",
        )

    t0 = time.time()

    # ── Decode image ─────────────────────────────────────────────────────────
    try:
        raw = base64.b64decode(request.image)
        img = Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Invalid image data: {exc}")

    img_w, img_h = img.size
    img_array = np.array(img)

    # ── Pre-processing pipeline ───────────────────────────────────────────────
    t_pre = time.time()
    try:
        preprocessed = _preprocess_for_ocr(img_array)
    except Exception as exc:
        logger.warning("Pre-processing failed (%s) — falling back to raw image", exc)
        preprocessed = img_array

    # Cap width to control memory; preprocessing preserves detail at higher DPI
    MAX_OCR_WIDTH = 1800
    pre_h, pre_w = preprocessed.shape[:2]
    if pre_w > MAX_OCR_WIDTH:
        scale = MAX_OCR_WIDTH / pre_w
        new_w = MAX_OCR_WIDTH
        new_h = int(pre_h * scale)
        preprocessed = cv2.resize(preprocessed, (new_w, new_h), interpolation=cv2.INTER_AREA)
        logger.debug("Resized %dx%d → %dx%d after preprocessing", pre_w, pre_h, new_w, new_h)

    logger.debug("Pre-processing took %.0fms", (time.time() - t_pre) * 1000)

    # ── Run both OCR models ───────────────────────────────────────────────────
    result_ja = None
    result_vi = None
    ja_error = vi_error = None

    try:
        result_ja = _ocr_ja.ocr(preprocessed, cls=True)
    except Exception as exc:
        ja_error = str(exc) or type(exc).__name__
        logger.exception("Japanese OCR engine error on %dx%d image", img_w, img_h)

    try:
        result_vi = _ocr_vi.ocr(preprocessed, cls=True)
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

    # ── Merge dual-model results ──────────────────────────────────────────────
    if ja_lines and vi_lines:
        merged_dual: list[tuple] = []
        # Re-build as (bbox, text, conf) triples for furigana detection;
        # _merge_dual_results only returns (text, conf) so we use ja_lines bboxes
        # for furigana detection on the merged result.
        # Simpler: run furigana detection on ja_lines (furigana lines are Japanese only),
        # then apply dual merge on the remaining lines.
        ja_after_furi, furi_count = _detect_and_merge_furigana_from_lines(ja_lines)
        merged_texts = _merge_dual_results_simple(ja_after_furi, vi_lines)
    elif ja_lines:
        ja_after_furi, furi_count = _detect_and_merge_furigana_from_lines(ja_lines)
        merged_texts = [(t, c) for t, c in ja_after_furi]
    elif vi_lines:
        merged_texts = [(text, conf) for _, text, conf in vi_lines]
        furi_count = 0
    else:
        elapsed_ms = int((time.time() - t0) * 1000)
        return OcrResponse(text="", rich_text="", confidence=0.0,
                           line_count=0, furigana_merged=0, processing_ms=elapsed_ms,
                           version="4.0.0", table_rows=[])

    texts = [t for t, _ in merged_texts]
    confidences = [c for _, c in merged_texts]
    avg_conf = sum(confidences) / len(confidences) if confidences else 0.0
    output_text = "\n".join(texts)
    elapsed_ms = int((time.time() - t0) * 1000)

    # ── Table row detection (runs on original ja_lines before furigana merge) ──
    # We use ja_lines because they carry bounding boxes required for X/Y clustering.
    # vi_lines are not used here since vocabulary words are always CJK.
    table_rows = _detect_table_rows(ja_lines) if ja_lines else []

    logger.info(
        "OCR v4: %d lines (furigana_merged=%d, table_rows=%d), conf=%.3f, %dms",
        len(texts), furi_count, len(table_rows), avg_conf, elapsed_ms,
    )

    return OcrResponse(
        text=output_text,
        rich_text=output_text,   # identical for now; future: HTML Ruby markup
        confidence=round(avg_conf, 4),
        line_count=len(texts),
        furigana_merged=furi_count,
        processing_ms=elapsed_ms,
        version="4.0.0",
        table_rows=table_rows,
    )


def _detect_and_merge_furigana_from_lines(lines: list) -> tuple[list[tuple[str, float]], int]:
    """Run furigana detection on (bbox, text, conf) triples and return (text, conf) pairs."""
    merged, count = _merge_furigana(lines)
    return merged, count


# ── Vocabulary Table Detection ────────────────────────────────────────────────

def _detect_table_rows(lines_with_bbox: list) -> list[str]:
    """
    Detect vocabulary table rows from raw PaddleOCR bounding-box lines.

    MnN vocabulary tables have a characteristic structure:
      • Fixed columns: number | Japanese word | reading | meaning | (example)
      • Each row spans a narrow Y range (~one text-line height)
      • At least one cell contains CJK/kana characters
      • At least 3 cells per row (serial no. + word + meaning minimum)

    Detection algorithm:
    1. Bucket all bounding boxes by rounded Y-center (25px tolerance)
    2. For each Y-bucket with ≥3 distinct X-positions, collect the texts
       in left-to-right order and join with tabs
    3. Filter rows where at least one cell contains CJK/kana — eliminates
       false positives from header lines in Latin/Vietnamese

    Returns list of tab-separated row strings, empty when no table detected.
    """
    if not lines_with_bbox:
        return []

    # Build (y_bucket, x_left, text) triples from raw OCR lines
    buckets: dict[int, list[tuple[int, str]]] = {}
    for bbox, text, _conf in lines_with_bbox:
        y_top = bbox[0][1]
        y_bot = bbox[2][1]
        y_center = int((y_top + y_bot) / 2)
        y_bucket = (y_center // 25) * 25  # 25px row tolerance
        x_left = int(bbox[0][0])
        if text.strip():
            buckets.setdefault(y_bucket, []).append((x_left, text.strip()))

    table_rows = []
    for y_bucket in sorted(buckets):
        cells = buckets[y_bucket]
        if len(cells) < 3:
            continue  # need ≥3 columns to be a table row
        # Sort cells left-to-right
        cells.sort(key=lambda c: c[0])
        texts = [c[1] for c in cells]
        # At least one cell must contain CJK/kana (vocabulary row guard)
        has_japanese = any(_contains_cjk(t) or _is_pure_kana(t) for t in texts)
        if not has_japanese:
            continue
        table_rows.append("\t".join(texts))

    return table_rows


def _merge_dual_results_simple(ja_lines_tc: list[tuple[str, float]],
                                vi_lines: list) -> list[tuple[str, float]]:
    """
    Simplified dual-merge after furigana detection.
    ja_lines_tc is already (text, conf); vi_lines is (bbox, text, conf).
    We merge by index position since both are sorted top-to-bottom.
    """
    result = []
    vi_tc = [(t, c) for _, t, c in vi_lines]
    max_len = max(len(ja_lines_tc), len(vi_tc))

    for i in range(max_len):
        if i < len(ja_lines_tc) and i < len(vi_tc):
            ja_t, ja_c = ja_lines_tc[i]
            vi_t, vi_c = vi_tc[i]
            if _contains_cjk(ja_t) and not _contains_vietnamese_diacritics(vi_t):
                result.append((ja_t, ja_c))
            elif _contains_vietnamese_diacritics(vi_t) and not _contains_cjk(ja_t):
                result.append((vi_t, vi_c))
            elif ja_c >= vi_c:
                result.append((ja_t, ja_c))
            else:
                result.append((vi_t, vi_c))
        elif i < len(ja_lines_tc):
            result.append(ja_lines_tc[i])
        else:
            vi_t, vi_c = vi_tc[i]
            result.append((vi_t, vi_c))

    return result


@app.get("/health")
async def health():
    return {
        "status": "ok" if _initialised else "initialising",
        "models": ["japan", "vi"],
        "version": "4.0.0",
        "features": ["preprocessing", "deskew", "furigana_detection", "table_detection"],
    }
