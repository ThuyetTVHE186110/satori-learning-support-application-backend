"""
Satori OCR Microservice
FastAPI + PaddleOCR 3.x — supports Japanese (hiragana, katakana, kanji) and English.

Model weights are downloaded once at container startup and cached in the volume
mounted at /root/.paddleocr (persisted across restarts via Docker named volume).

Endpoints:
  POST /ocr   { "image": "<base64 PNG/JPG>" }
            → { "text": "...", "confidence": 0.97, "line_count": 42, "processing_ms": 380 }
  GET  /health → { "status": "ok" | "initialising", "model": "japan" }
"""

import base64
import io
import logging
import os
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

# ── Global OCR instance ─────────────────────────────────────────────────────
_ocr = None
_initialised = False


def _init_ocr():
    """Initialise PaddleOCR. Called once from the lifespan handler."""
    global _ocr, _initialised
    from paddleocr import PaddleOCR

    lang = os.environ.get("MODEL_LANG", "japan")
    logger.info("Initialising PaddleOCR (lang=%s) — model download may take a few minutes…", lang)
    t0 = time.time()

    _ocr = PaddleOCR(
        lang=lang,
        use_angle_cls=True,
        show_log=False,
        use_gpu=False,
    )
    _initialised = True
    logger.info("PaddleOCR ready in %.1fs", time.time() - t0)


# ── FastAPI lifespan (startup / shutdown) ────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    # Run heavy initialisation in a thread so the event loop stays responsive
    import asyncio
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, _init_ocr)
    yield
    # Shutdown — nothing to clean up


app = FastAPI(title="Satori OCR Service", version="1.1.0", lifespan=lifespan)


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
        raise HTTPException(status_code=503, detail="OCR engine is still initialising — retry in a moment.")

    t0 = time.time()

    try:
        raw = base64.b64decode(request.image)
        img = Image.open(io.BytesIO(raw)).convert("RGB")
        img_array = np.array(img)
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"Invalid image data: {exc}")

    try:
        result = _ocr.ocr(img_array, cls=True)
    except Exception as exc:
        logger.exception("OCR engine error")
        raise HTTPException(status_code=500, detail=f"OCR engine error: {exc}")

    lines: list[str] = []
    confidences: list[float] = []

    if result and result[0]:
        for block in result:
            if block is None:
                continue
            for line in block:
                text_val = line[1][0].strip()
                conf_val = float(line[1][1])
                if text_val:
                    lines.append(text_val)
                    confidences.append(conf_val)

    avg_conf = sum(confidences) / len(confidences) if confidences else 0.0
    elapsed_ms = int((time.time() - t0) * 1000)

    logger.info("OCR: %d lines, conf=%.3f, %dms", len(lines), avg_conf, elapsed_ms)

    return OcrResponse(
        text="\n".join(lines),
        confidence=round(avg_conf, 4),
        line_count=len(lines),
        processing_ms=elapsed_ms,
    )


@app.get("/health")
async def health():
    lang = os.environ.get("MODEL_LANG", "japan")
    return {
        "status": "ok" if _initialised else "initialising",
        "model": lang,
    }
