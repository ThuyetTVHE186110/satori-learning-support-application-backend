"""
Japanese Pronunciation Assessment Microservice
================================================
Assesses pitch accent and mora timing for Japanese speech.

Endpoints:
  POST /assess  - Multipart: audio file + reference_text -> JSON scores
  GET  /health  - Health check

Uses pyopenjtalk for expected accent patterns and mora segmentation,
librosa.pyin for F0 extraction from audio.
"""

import io
import time
import logging
from typing import Optional

import numpy as np
import librosa
import soundfile as sf
from fastapi import FastAPI, File, Form, UploadFile, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

try:
    import pyopenjtalk
except ImportError:
    pyopenjtalk = None

app = FastAPI(title="Japanese Pronunciation Assessment", version="1.0.0")
logger = logging.getLogger("jp-pronunciation")
logging.basicConfig(level=logging.INFO)

# Cached health status: validated once at startup to avoid pyopenjtalk call per request
_jtalk_healthy: bool = False

# ─── Constants ───────────────────────────────────────────────────────────────

SAMPLE_RATE = 16000
MIN_AUDIO_DURATION = 0.3  # seconds
MAX_AUDIO_DURATION = 60.0  # seconds
PITCH_FMIN = 75.0
PITCH_FMAX = 600.0

# Mora timing tolerances (ratio relative to average mora duration)
REGULAR_MORA_TOLERANCE = 0.35
SOKUON_MIN_RATIO = 0.6  # っ should be ~1.0x average
SOKUON_MAX_RATIO = 1.5
LONG_VOWEL_MIN_RATIO = 1.5  # long vowel pair ~2.0x average
LONG_VOWEL_MAX_RATIO = 2.8
HATSUON_MIN_RATIO = 0.6  # ん should be ~1.0x
HATSUON_MAX_RATIO = 1.5


# ─── Response Models ─────────────────────────────────────────────────────────

class WordDetail(BaseModel):
    word: str
    reading: str
    expected_pattern: str
    detected_pattern: str
    pitch_correct: bool


class MoraDetail(BaseModel):
    mora: str
    expected_relative_duration: float
    actual_relative_duration: float
    status: str  # "good", "too_short", "too_long"
    mora_type: str  # "regular", "sokuon", "hatsuon", "long_vowel"


class DetailedFeedback(BaseModel):
    words: list[WordDetail]
    mora_details: list[MoraDetail]
    sokuon_accuracy: Optional[float] = None
    hatsuon_accuracy: Optional[float] = None
    long_vowel_accuracy: Optional[float] = None


class AssessmentResponse(BaseModel):
    success: bool
    pitch_accent_score: float = 0.0
    mora_timing_score: float = 0.0
    detailed_feedback: Optional[DetailedFeedback] = None
    processing_ms: int = 0
    error_message: Optional[str] = None


# ─── Utility: Katakana Mora Splitter ─────────────────────────────────────────

# Special mora markers
SMALL_KANA = set("ァィゥェォャュョヮ")
SOKUON = "ッ"
HATSUON = "ン"
LONG_VOWEL = "ー"


def split_katakana_to_mora(katakana: str) -> list[str]:
    """Split katakana string into mora units."""
    moras = []
    i = 0
    while i < len(katakana):
        ch = katakana[i]
        # Check if next char is a small kana (forms compound mora)
        if i + 1 < len(katakana) and katakana[i + 1] in SMALL_KANA:
            moras.append(ch + katakana[i + 1])
            i += 2
        else:
            moras.append(ch)
            i += 1
    return moras


def classify_mora(mora: str) -> str:
    """Classify a mora as regular, sokuon, hatsuon, or long_vowel."""
    if mora == SOKUON:
        return "sokuon"
    elif mora == HATSUON:
        return "hatsuon"
    elif mora == LONG_VOWEL:
        return "long_vowel"
    return "regular"


# ─── Pitch Accent Analysis ──────────────────────────────────────────────────

def get_accent_pattern(text: str) -> list[dict]:
    """
    Use pyopenjtalk.run_frontend() to extract expected accent patterns.
    Returns list of word dicts with accent info.
    """
    if pyopenjtalk is None:
        return []

    try:
        njd_features = pyopenjtalk.run_frontend(text)
    except Exception as e:
        logger.warning("pyopenjtalk.run_frontend failed: %s", e)
        return []

    words = []
    for feat in njd_features:
        # Each feature is a dict-like string; parse relevant fields
        word_str = feat.get("string", "") if isinstance(feat, dict) else str(feat)
        reading = feat.get("read", "") if isinstance(feat, dict) else ""
        acc_pos = 0

        if isinstance(feat, dict):
            word_str = feat.get("string", "")
            reading = feat.get("read", "") if feat.get("read") else feat.get("pron", "")
            acc_pos = int(feat.get("acc", 0)) if feat.get("acc") is not None else 0
        else:
            # pyopenjtalk returns NJDFeature objects
            try:
                word_str = feat.string if hasattr(feat, "string") else str(feat)
                reading = feat.read if hasattr(feat, "read") else ""
                acc_pos = int(feat.acc) if hasattr(feat, "acc") else 0
            except Exception:
                continue

        if not reading or reading == "*":
            continue

        moras = split_katakana_to_mora(reading)
        n_mora = len(moras)

        # Generate H/L pattern from accent position
        # acc_pos = 0: flat (heiban) - LHHH...H
        # acc_pos = 1: atamadaka - HLLL...L
        # acc_pos = n: nakadaka/odaka - LHHHH..HL...L (drop after acc_pos)
        pattern = []
        if acc_pos == 0:
            # Heiban: L H H H ... H (first mora low, rest high)
            pattern = ["L"] + ["H"] * (n_mora - 1) if n_mora > 1 else ["H"]
        elif acc_pos == 1:
            # Atamadaka: H L L ... L
            pattern = ["H"] + ["L"] * (n_mora - 1) if n_mora > 1 else ["H"]
        else:
            # Nakadaka/Odaka: L H H ... H L L ... L (drop after acc_pos)
            for i in range(n_mora):
                if i == 0:
                    pattern.append("L")
                elif i < acc_pos:
                    pattern.append("H")
                else:
                    pattern.append("L")

        words.append({
            "word": word_str,
            "reading": reading,
            "moras": moras,
            "accent_position": acc_pos,
            "expected_pattern": pattern,
        })

    return words


def extract_f0_contour(audio: np.ndarray, sr: int) -> np.ndarray:
    """Extract F0 using librosa.pyin."""
    f0, voiced_flag, voiced_probs = librosa.pyin(
        audio, fmin=PITCH_FMIN, fmax=PITCH_FMAX, sr=sr
    )
    return f0


def estimate_pitch_pattern_for_moras(
    f0: np.ndarray, sr: int, hop_length: int, moras: list[str], total_duration: float
) -> list[str]:
    """
    Estimate detected H/L pattern for each mora from F0 contour.
    Uses proportional timing (each mora gets equal time slice).
    """
    n_moras = len(moras)
    if n_moras == 0:
        return []

    mora_duration = total_duration / n_moras
    n_frames = len(f0)
    frame_duration = hop_length / sr

    # Get median F0 for voiced frames (to determine H/L threshold)
    voiced_f0 = f0[~np.isnan(f0)]
    if len(voiced_f0) < 2:
        return ["?" for _ in moras]

    median_f0 = np.median(voiced_f0)

    pattern = []
    for i in range(n_moras):
        start_time = i * mora_duration
        end_time = (i + 1) * mora_duration
        start_frame = int(start_time / frame_duration)
        end_frame = int(end_time / frame_duration)
        start_frame = max(0, min(start_frame, n_frames - 1))
        end_frame = max(start_frame + 1, min(end_frame, n_frames))

        segment_f0 = f0[start_frame:end_frame]
        voiced_segment = segment_f0[~np.isnan(segment_f0)]

        if len(voiced_segment) == 0:
            pattern.append("?")  # Unvoiced / silence
        else:
            avg_f0 = np.mean(voiced_segment)
            # Use threshold slightly below median to account for natural declination
            if avg_f0 >= median_f0 * 0.95:
                pattern.append("H")
            else:
                pattern.append("L")

    return pattern


def score_pitch_accent(expected: list[str], detected: list[str]) -> tuple[bool, float]:
    """
    Compare expected vs detected pitch pattern.
    Returns (is_correct, score_0_to_100).
    """
    if not expected or not detected:
        return True, 100.0

    n = min(len(expected), len(detected))
    matches = 0
    total = 0

    for i in range(n):
        if detected[i] == "?":
            # Skip unvoiced segments (give benefit of doubt)
            continue
        total += 1
        if expected[i] == detected[i]:
            matches += 1

    if total == 0:
        return True, 100.0

    score = (matches / total) * 100.0
    is_correct = score >= 80.0  # 80% threshold for "correct"
    return is_correct, score


# ─── Mora Timing Analysis ───────────────────────────────────────────────────

def analyze_mora_timing(
    audio: np.ndarray, sr: int, moras: list[str]
) -> tuple[list[MoraDetail], float]:
    """
    Analyze mora timing by estimating duration ratios.
    Returns (mora_details, timing_score).
    """
    n_moras = len(moras)
    if n_moras == 0:
        return [], 100.0

    total_duration = len(audio) / sr
    avg_mora_duration = total_duration / n_moras

    # Use energy-based segmentation for better mora boundary estimation
    hop_length = int(sr * 0.01)  # 10ms hop
    energy = librosa.feature.rms(y=audio, hop_length=hop_length)[0]
    frame_duration = hop_length / sr

    # Estimate mora boundaries proportionally (simple approach)
    mora_details = []
    scores = []

    for i in range(n_moras):
        mora = moras[i]
        mora_type = classify_mora(mora)

        start_time = i * avg_mora_duration
        end_time = (i + 1) * avg_mora_duration
        start_frame = int(start_time / frame_duration)
        end_frame = int(end_time / frame_duration)
        start_frame = max(0, min(start_frame, len(energy) - 1))
        end_frame = max(start_frame + 1, min(end_frame, len(energy)))

        # Calculate actual energy-weighted duration ratio
        segment_energy = energy[start_frame:end_frame]
        if np.sum(energy) > 0:
            energy_ratio = np.sum(segment_energy) / (np.sum(energy) / n_moras)
        else:
            energy_ratio = 1.0

        # Expected ratio based on mora type
        expected_ratio = 1.0
        if mora_type == "long_vowel":
            expected_ratio = 2.0  # long vowels span ~2 mora units
        elif mora_type == "sokuon":
            expected_ratio = 1.0  # geminate is ~1 mora of silence

        # Score this mora
        status = "good"
        mora_score = 100.0

        if mora_type == "regular":
            deviation = abs(energy_ratio - 1.0)
            if deviation > REGULAR_MORA_TOLERANCE:
                status = "too_short" if energy_ratio < 1.0 else "too_long"
                mora_score = max(0, 100 - (deviation - REGULAR_MORA_TOLERANCE) * 200)
        elif mora_type == "sokuon":
            if energy_ratio < SOKUON_MIN_RATIO:
                status = "too_short"
                mora_score = max(0, 100 - (SOKUON_MIN_RATIO - energy_ratio) * 200)
            elif energy_ratio > SOKUON_MAX_RATIO:
                status = "too_long"
                mora_score = max(0, 100 - (energy_ratio - SOKUON_MAX_RATIO) * 200)
        elif mora_type == "hatsuon":
            if energy_ratio < HATSUON_MIN_RATIO:
                status = "too_short"
                mora_score = max(0, 100 - (HATSUON_MIN_RATIO - energy_ratio) * 200)
            elif energy_ratio > HATSUON_MAX_RATIO:
                status = "too_long"
                mora_score = max(0, 100 - (energy_ratio - HATSUON_MAX_RATIO) * 200)
        elif mora_type == "long_vowel":
            if energy_ratio < LONG_VOWEL_MIN_RATIO:
                status = "too_short"
                mora_score = max(0, 100 - (LONG_VOWEL_MIN_RATIO - energy_ratio) * 100)
            elif energy_ratio > LONG_VOWEL_MAX_RATIO:
                status = "too_long"
                mora_score = max(0, 100 - (energy_ratio - LONG_VOWEL_MAX_RATIO) * 100)

        scores.append(mora_score)
        mora_details.append(MoraDetail(
            mora=mora,
            expected_relative_duration=round(expected_ratio, 2),
            actual_relative_duration=round(energy_ratio, 2),
            status=status,
            mora_type=mora_type,
        ))

    timing_score = sum(scores) / len(scores) if scores else 100.0
    return mora_details, timing_score


# ─── Special Mora Accuracy ──────────────────────────────────────────────────

def compute_special_mora_accuracy(mora_details: list[MoraDetail]) -> dict:
    """Compute accuracy for each special mora type."""
    result = {}
    for mora_type in ["sokuon", "hatsuon", "long_vowel"]:
        typed = [m for m in mora_details if m.mora_type == mora_type]
        if typed:
            good_count = sum(1 for m in typed if m.status == "good")
            result[f"{mora_type}_accuracy"] = round(good_count / len(typed) * 100, 1)
        else:
            result[f"{mora_type}_accuracy"] = None
    return result


# ─── Main Assessment Logic ───────────────────────────────────────────────────

def assess_pronunciation(audio_bytes: bytes, reference_text: str) -> AssessmentResponse:
    """Run full pitch accent + mora timing assessment."""
    start = time.time()

    if pyopenjtalk is None:
        return AssessmentResponse(
            success=False,
            error_message="pyopenjtalk is not installed",
        )

    # 1. Load audio
    try:
        audio_io = io.BytesIO(audio_bytes)
        audio, sr = sf.read(audio_io)

        # Convert to mono if stereo
        if audio.ndim > 1:
            audio = np.mean(audio, axis=1)

        # Resample to 16kHz if needed
        if sr != SAMPLE_RATE:
            logger.info("Resampling audio from %d Hz to %d Hz", sr, SAMPLE_RATE)
            audio = librosa.resample(audio, orig_sr=sr, target_sr=SAMPLE_RATE)
            sr = SAMPLE_RATE

        duration = len(audio) / sr
        if duration < MIN_AUDIO_DURATION:
            return AssessmentResponse(
                success=False,
                error_message=f"Audio too short ({duration:.1f}s, minimum {MIN_AUDIO_DURATION}s)",
            )
        if duration > MAX_AUDIO_DURATION:
            return AssessmentResponse(
                success=False,
                error_message=f"Audio too long ({duration:.1f}s, maximum {MAX_AUDIO_DURATION}s)",
            )

    except Exception as e:
        return AssessmentResponse(
            success=False,
            error_message=f"Failed to load audio: {str(e)}",
        )

    # 2. Get expected accent patterns from text
    word_accents = get_accent_pattern(reference_text)
    if not word_accents:
        return AssessmentResponse(
            success=False,
            error_message="Could not extract accent pattern from text",
        )

    # 3. Collect all moras across all words
    all_moras = []
    all_expected_patterns = []
    for w in word_accents:
        all_moras.extend(w["moras"])
        all_expected_patterns.extend(w["expected_pattern"])

    if not all_moras:
        return AssessmentResponse(
            success=False,
            error_message="No moras extracted from text",
        )

    # 4. Extract F0 and estimate detected pattern
    hop_length = 256
    f0 = extract_f0_contour(audio, sr)
    total_duration = len(audio) / sr

    detected_pattern = estimate_pitch_pattern_for_moras(
        f0, sr, hop_length, all_moras, total_duration
    )

    # 5. Score pitch accent per word
    word_details = []
    mora_offset = 0
    pitch_scores = []

    for w in word_accents:
        n = len(w["moras"])
        exp_slice = all_expected_patterns[mora_offset:mora_offset + n]
        det_slice = detected_pattern[mora_offset:mora_offset + n]

        is_correct, word_pitch_score = score_pitch_accent(exp_slice, det_slice)
        pitch_scores.append(word_pitch_score)

        word_details.append(WordDetail(
            word=w["word"],
            reading=w["reading"],
            expected_pattern="".join(exp_slice),
            detected_pattern="".join(det_slice),
            pitch_correct=is_correct,
        ))
        mora_offset += n

    pitch_accent_score = sum(pitch_scores) / len(pitch_scores) if pitch_scores else 0.0

    # 6. Analyze mora timing
    mora_details, mora_timing_score = analyze_mora_timing(audio, sr, all_moras)

    # 7. Special mora accuracy
    special_acc = compute_special_mora_accuracy(mora_details)

    processing_ms = int((time.time() - start) * 1000)

    return AssessmentResponse(
        success=True,
        pitch_accent_score=round(pitch_accent_score, 1),
        mora_timing_score=round(mora_timing_score, 1),
        detailed_feedback=DetailedFeedback(
            words=word_details,
            mora_details=mora_details,
            sokuon_accuracy=special_acc["sokuon_accuracy"],
            hatsuon_accuracy=special_acc["hatsuon_accuracy"],
            long_vowel_accuracy=special_acc["long_vowel_accuracy"],
        ),
        processing_ms=processing_ms,
    )


# ─── Endpoints ───────────────────────────────────────────────────────────────

@app.post("/assess", response_model=AssessmentResponse)
async def assess(
    audio: UploadFile = File(...),
    reference_text: str = Form(...),
):
    """Assess Japanese pronunciation (pitch accent + mora timing)."""
    try:
        audio_bytes = await audio.read()
        if len(audio_bytes) == 0:
            raise HTTPException(status_code=400, detail="Empty audio file")
        if len(audio_bytes) > 10 * 1024 * 1024:
            raise HTTPException(status_code=400, detail="Audio file exceeds 10MB limit")

        stripped_text = reference_text.strip()
        if not stripped_text:
            raise HTTPException(status_code=400, detail="reference_text is required")
        if len(stripped_text) > 1000:
            raise HTTPException(status_code=400, detail="reference_text exceeds 1000 character limit")

        result = assess_pronunciation(audio_bytes, stripped_text)
        return result

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Assessment failed")
        return AssessmentResponse(
            success=False,
            error_message=f"Internal error: {str(e)}",
        )


@app.on_event("startup")
def _validate_jtalk():
    """Run pyopenjtalk smoke test once at startup and cache the result."""
    global _jtalk_healthy
    if pyopenjtalk is not None:
        try:
            pyopenjtalk.g2p("テスト", kana=True)
            _jtalk_healthy = True
            logger.info("pyopenjtalk smoke test passed")
        except Exception as e:
            logger.warning("pyopenjtalk smoke test failed: %s", e)
            _jtalk_healthy = False
    else:
        _jtalk_healthy = False
        logger.warning("pyopenjtalk not installed")


@app.get("/health")
async def health():
    """Health check endpoint (lightweight, no computation)."""
    return {
        "status": "healthy" if _jtalk_healthy else "degraded",
        "pyopenjtalk": _jtalk_healthy,
        "version": "1.0.0",
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8091)
