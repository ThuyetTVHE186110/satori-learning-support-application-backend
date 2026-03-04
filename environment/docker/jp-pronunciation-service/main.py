"""
Japanese Pronunciation Assessment Microservice
================================================
Assesses pitch accent and mora timing for Japanese speech.

Endpoints:
  POST /assess   - Multipart: audio file + reference_text -> JSON scores
  GET  /health   - Health check
  GET  /metrics  - Request counts, latencies, error breakdown

Uses pyopenjtalk for expected accent patterns and mora segmentation,
librosa.pyin for F0 extraction from audio.
"""

import asyncio
import io
import os
import re
import time
import logging
import threading
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

app = FastAPI(title="Japanese Pronunciation Assessment", version="1.2.0")
logger = logging.getLogger("jp-pronunciation")
logging.basicConfig(level=logging.INFO)

# Cached health status: validated once at startup to avoid pyopenjtalk call per request
_jtalk_healthy: bool = False

# ─── Constants (configurable via environment variables) ──────────────────────

SAMPLE_RATE = 16000
MIN_AUDIO_DURATION = 0.3  # seconds
MAX_AUDIO_DURATION = 60.0  # seconds

PITCH_FMIN = 75.0
PITCH_FMAX = 600.0

# Mora timing tolerances (ratio relative to average mora duration)
REGULAR_MORA_TOLERANCE = float(os.environ.get("MORA_REGULAR_TOLERANCE", "0.35"))
SOKUON_MIN_RATIO = float(os.environ.get("MORA_SOKUON_MIN", "0.6"))
SOKUON_MAX_RATIO = float(os.environ.get("MORA_SOKUON_MAX", "1.5"))
LONG_VOWEL_MIN_RATIO = float(os.environ.get("MORA_LONG_VOWEL_MIN", "1.5"))
LONG_VOWEL_MAX_RATIO = float(os.environ.get("MORA_LONG_VOWEL_MAX", "2.8"))
HATSUON_MIN_RATIO = float(os.environ.get("MORA_HATSUON_MIN", "0.6"))
HATSUON_MAX_RATIO = float(os.environ.get("MORA_HATSUON_MAX", "1.5"))

# Pitch accent scoring threshold
PITCH_CORRECT_THRESHOLD = float(os.environ.get("PITCH_CORRECT_THRESHOLD", "80.0"))

# Per-request assessment timeout (seconds)
ASSESSMENT_TIMEOUT = float(os.environ.get("ASSESSMENT_TIMEOUT_SECONDS", "30"))

# Accent cache TTL (seconds); 0 = no expiry
ACCENT_CACHE_TTL = int(os.environ.get("ACCENT_CACHE_TTL_SECONDS", "3600"))

# Japanese character detection (hiragana, katakana, CJK unified ideographs)
_JAPANESE_PATTERN = re.compile(
    r'[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF]'
)


# ─── Metrics ────────────────────────────────────────────────────────────────

class Metrics:
    """Thread-safe request metrics collector."""

    def __init__(self):
        self._lock = threading.Lock()
        self.total_requests = 0
        self.successful = 0
        self.failed = 0
        self.timeouts = 0
        self.total_latency_ms = 0
        self.error_counts: dict[str, int] = {}

    def record_success(self, latency_ms: int):
        with self._lock:
            self.total_requests += 1
            self.successful += 1
            self.total_latency_ms += latency_ms

    def record_failure(self, error_type: str, latency_ms: int):
        with self._lock:
            self.total_requests += 1
            self.failed += 1
            self.total_latency_ms += latency_ms
            self.error_counts[error_type] = self.error_counts.get(error_type, 0) + 1

    def record_timeout(self):
        with self._lock:
            self.total_requests += 1
            self.timeouts += 1

    def snapshot(self) -> dict:
        with self._lock:
            avg_latency = (
                self.total_latency_ms / self.total_requests
                if self.total_requests > 0
                else 0
            )
            return {
                "total_requests": self.total_requests,
                "successful": self.successful,
                "failed": self.failed,
                "timeouts": self.timeouts,
                "avg_latency_ms": round(avg_latency, 1),
                "errors_by_type": dict(self.error_counts),
            }


_metrics = Metrics()


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


# ─── Utility: Input Validation ───────────────────────────────────────────────

def contains_japanese(text: str) -> bool:
    """Check whether the text contains at least one Japanese character."""
    return bool(_JAPANESE_PATTERN.search(text))


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


# ─── Fallback Mora Extraction ────────────────────────────────────────────────

def get_moras_from_text(text: str) -> list[str]:
    """
    Fallback mora extraction when pyopenjtalk.run_frontend() fails.
    Tries g2p first, then direct kana splitting for hiragana/katakana input.
    """
    # Try g2p (different code path than run_frontend, may still work)
    if pyopenjtalk is not None:
        try:
            katakana = pyopenjtalk.g2p(text, kana=True)
            if katakana:
                return split_katakana_to_mora(katakana)
        except Exception:
            pass

    # Direct kana splitting: convert hiragana to katakana, keep katakana
    kana_text = ""
    for ch in text:
        cp = ord(ch)
        if 0x3040 <= cp <= 0x309F:  # hiragana -> katakana
            kana_text += chr(cp + 0x60)
        elif 0x30A0 <= cp <= 0x30FF:  # already katakana
            kana_text += ch

    if kana_text:
        return split_katakana_to_mora(kana_text)
    return []


# ─── Accent Pattern Cache (with TTL) ────────────────────────────────────────

_accent_cache: dict[str, tuple[float, list[dict]]] = {}  # key -> (timestamp, value)
_ACCENT_CACHE_MAX = 256


def _cache_get(text: str) -> Optional[list[dict]]:
    """Retrieve from cache if present and not expired."""
    entry = _accent_cache.get(text)
    if entry is None:
        return None
    ts, value = entry
    if ACCENT_CACHE_TTL > 0 and (time.time() - ts) >= ACCENT_CACHE_TTL:
        del _accent_cache[text]
        return None
    return value


def _cache_put(text: str, value: list[dict]):
    """Store in cache, evicting oldest if full."""
    if len(_accent_cache) >= _ACCENT_CACHE_MAX:
        oldest_key = next(iter(_accent_cache))
        del _accent_cache[oldest_key]
    _accent_cache[text] = (time.time(), value)


def get_accent_pattern(text: str) -> list[dict]:
    """
    Use pyopenjtalk.run_frontend() to extract expected accent patterns.
    Returns list of word dicts with accent info. Results are cached with TTL.
    """
    cached = _cache_get(text)
    if cached is not None:
        return cached

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

    _cache_put(text, words)
    return words


# ─── Mora Boundary Estimation ───────────────────────────────────────────────

def estimate_mora_boundaries(
    audio: np.ndarray, sr: int, n_moras: int
) -> list[float]:
    """
    Estimate mora boundary timestamps using onset detection.
    Falls back to equal division if onset count doesn't match.
    Returns list of n_moras + 1 boundary times.
    """
    total_duration = len(audio) / sr
    if n_moras <= 1:
        return [0.0, total_duration]

    try:
        onsets = librosa.onset.onset_detect(
            y=audio, sr=sr, units='time', backtrack=True
        )
    except Exception:
        onsets = np.array([])

    # Filter onsets to be within valid range
    if len(onsets) > 0:
        onsets = onsets[(onsets > 0.01) & (onsets < total_duration - 0.01)]

    # If onset count matches expected boundary count, use them directly
    if len(onsets) >= n_moras - 1:
        boundaries = [0.0] + sorted(onsets[:n_moras - 1].tolist()) + [total_duration]
    elif len(onsets) > 0 and len(onsets) >= n_moras // 2:
        # Partial onset info: snap proportional boundaries to nearby onsets
        boundaries = _interpolate_boundaries(onsets, n_moras, total_duration)
    else:
        # Fall back to equal division
        boundaries = [i * total_duration / n_moras for i in range(n_moras + 1)]

    return boundaries


def _interpolate_boundaries(
    onsets: np.ndarray, n_moras: int, total_duration: float
) -> list[float]:
    """Interpolate mora boundaries using detected onsets as anchor points."""
    # Start with evenly spaced boundaries
    boundaries = list(np.linspace(0, total_duration, n_moras + 1))

    # Snap each interior boundary to the nearest onset if close enough
    onset_list = sorted(onsets.tolist())
    max_snap_distance = total_duration / n_moras * 0.4

    for onset_t in onset_list:
        best_idx = -1
        best_dist = max_snap_distance
        for k in range(1, len(boundaries) - 1):
            dist = abs(boundaries[k] - onset_t)
            if dist < best_dist:
                best_dist = dist
                best_idx = k
        if best_idx > 0:
            boundaries[best_idx] = onset_t

    # Ensure boundaries remain strictly increasing
    for k in range(1, len(boundaries)):
        if boundaries[k] <= boundaries[k - 1]:
            boundaries[k] = boundaries[k - 1] + 0.001

    return boundaries


# ─── Pitch Accent Analysis ──────────────────────────────────────────────────

def extract_f0_contour(audio: np.ndarray, sr: int) -> np.ndarray:
    """Extract F0 using librosa.pyin."""
    f0, voiced_flag, voiced_probs = librosa.pyin(
        audio, fmin=PITCH_FMIN, fmax=PITCH_FMAX, sr=sr
    )
    return f0


def estimate_pitch_pattern_for_moras(
    f0: np.ndarray, sr: int, hop_length: int, moras: list[str],
    boundaries: list[float],
) -> list[str]:
    """
    Estimate detected H/L pattern for each mora from F0 contour.
    Uses onset-based mora boundaries, speaker-normalized pitch with
    percentile-based threshold, and declination compensation.
    """
    n_moras = len(moras)
    if n_moras == 0:
        return []

    n_frames = len(f0)
    frame_duration = hop_length / sr

    # Get voiced F0 values for speaker normalization
    voiced_f0 = f0[~np.isnan(f0)]
    if len(voiced_f0) < 2:
        return ["?" for _ in moras]

    # Speaker normalization: use interquartile range midpoint as H/L boundary.
    # This adapts to any speaker's natural pitch range regardless of whether
    # they have a high or low voice.
    q25 = np.percentile(voiced_f0, 25)
    q75 = np.percentile(voiced_f0, 75)
    threshold = (q25 + q75) / 2

    # Natural declination compensation: pitch typically drops gradually across
    # an utterance, so lower the threshold slightly for later moras.
    declination_rate = 0.005  # 0.5% per mora

    pattern = []
    for i in range(n_moras):
        start_time = boundaries[i]
        end_time = boundaries[i + 1]
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
            # Adjust threshold for natural pitch declination over time
            adjusted_threshold = threshold * (1 - declination_rate * i)
            if avg_f0 >= adjusted_threshold:
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
    is_correct = score >= PITCH_CORRECT_THRESHOLD
    return is_correct, score


# ─── Mora Timing Analysis ───────────────────────────────────────────────────

def analyze_mora_timing(
    audio: np.ndarray, sr: int, moras: list[str], boundaries: list[float],
) -> tuple[list[MoraDetail], float]:
    """
    Analyze mora timing by estimating duration ratios.
    Uses pre-computed onset-based boundaries and blends duration ratio with
    energy ratio for a more robust combined estimate.
    Returns (mora_details, timing_score).
    """
    n_moras = len(moras)
    if n_moras == 0:
        return [], 100.0

    total_duration = len(audio) / sr
    avg_mora_duration = total_duration / n_moras

    # Compute energy for validation
    hop_length = int(sr * 0.01)  # 10ms hop
    energy = librosa.feature.rms(y=audio, hop_length=hop_length)[0]
    frame_duration = hop_length / sr

    mora_details = []
    scores = []

    for i in range(n_moras):
        mora = moras[i]
        mora_type = classify_mora(mora)

        start_time = boundaries[i]
        end_time = boundaries[i + 1]
        segment_duration = end_time - start_time

        # Duration ratio relative to average mora
        duration_ratio = segment_duration / avg_mora_duration if avg_mora_duration > 0 else 1.0

        # Energy-based ratio for cross-validation
        start_frame = int(start_time / frame_duration)
        end_frame = int(end_time / frame_duration)
        start_frame = max(0, min(start_frame, len(energy) - 1))
        end_frame = max(start_frame + 1, min(end_frame, len(energy)))

        segment_energy = energy[start_frame:end_frame]
        if np.sum(energy) > 0:
            energy_ratio = np.sum(segment_energy) / (np.sum(energy) / n_moras)
        else:
            energy_ratio = 1.0

        # Blend duration ratio (from onset boundaries) with energy ratio
        combined_ratio = 0.6 * duration_ratio + 0.4 * energy_ratio

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
            deviation = abs(combined_ratio - 1.0)
            if deviation > REGULAR_MORA_TOLERANCE:
                status = "too_short" if combined_ratio < 1.0 else "too_long"
                mora_score = max(0, 100 - (deviation - REGULAR_MORA_TOLERANCE) * 200)
        elif mora_type == "sokuon":
            if combined_ratio < SOKUON_MIN_RATIO:
                status = "too_short"
                mora_score = max(0, 100 - (SOKUON_MIN_RATIO - combined_ratio) * 200)
            elif combined_ratio > SOKUON_MAX_RATIO:
                status = "too_long"
                mora_score = max(0, 100 - (combined_ratio - SOKUON_MAX_RATIO) * 200)
        elif mora_type == "hatsuon":
            if combined_ratio < HATSUON_MIN_RATIO:
                status = "too_short"
                mora_score = max(0, 100 - (HATSUON_MIN_RATIO - combined_ratio) * 200)
            elif combined_ratio > HATSUON_MAX_RATIO:
                status = "too_long"
                mora_score = max(0, 100 - (combined_ratio - HATSUON_MAX_RATIO) * 200)
        elif mora_type == "long_vowel":
            if combined_ratio < LONG_VOWEL_MIN_RATIO:
                status = "too_short"
                mora_score = max(0, 100 - (LONG_VOWEL_MIN_RATIO - combined_ratio) * 100)
            elif combined_ratio > LONG_VOWEL_MAX_RATIO:
                status = "too_long"
                mora_score = max(0, 100 - (combined_ratio - LONG_VOWEL_MAX_RATIO) * 100)

        scores.append(mora_score)
        mora_details.append(MoraDetail(
            mora=mora,
            expected_relative_duration=round(expected_ratio, 2),
            actual_relative_duration=round(combined_ratio, 2),
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
    """Run full pitch accent + mora timing assessment with graceful degradation."""
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

    # Collect all moras and expected patterns
    all_moras = []
    all_expected_patterns = []
    for w in word_accents:
        all_moras.extend(w["moras"])
        all_expected_patterns.extend(w["expected_pattern"])

    # Graceful degradation: if accent parsing failed, try fallback mora extraction
    # so we can still provide timing analysis even without pitch accent data
    pitch_available = bool(all_moras)
    if not pitch_available:
        logger.warning("Accent pattern extraction failed; attempting fallback mora extraction")
        all_moras = get_moras_from_text(reference_text)
        if not all_moras:
            return AssessmentResponse(
                success=False,
                error_message="Could not extract mora information from text",
            )

    # 3. Compute onset-based mora boundaries once, shared by pitch and timing
    boundaries = estimate_mora_boundaries(audio, sr, len(all_moras))

    # 4. Pitch accent analysis (only if accent data is available)
    word_details = []
    pitch_accent_score = 0.0

    if pitch_available:
        hop_length = 256
        f0 = extract_f0_contour(audio, sr)

        detected_pattern = estimate_pitch_pattern_for_moras(
            f0, sr, hop_length, all_moras, boundaries
        )

        # Score pitch accent per word
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

    # 5. Analyze mora timing (always available, uses same boundaries)
    mora_details, mora_timing_score = analyze_mora_timing(audio, sr, all_moras, boundaries)

    # 6. Special mora accuracy
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
    req_start = time.time()
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

        # Validate that the text contains Japanese characters
        if not contains_japanese(stripped_text):
            raise HTTPException(
                status_code=400,
                detail="reference_text must contain Japanese characters (hiragana, katakana, or kanji)",
            )

        # Run CPU-bound assessment in a thread with timeout
        try:
            result = await asyncio.wait_for(
                asyncio.to_thread(assess_pronunciation, audio_bytes, stripped_text),
                timeout=ASSESSMENT_TIMEOUT,
            )
        except asyncio.TimeoutError:
            _metrics.record_timeout()
            return AssessmentResponse(
                success=False,
                error_message=f"Assessment timed out after {ASSESSMENT_TIMEOUT:.0f}s",
            )

        latency_ms = int((time.time() - req_start) * 1000)
        if result.success:
            _metrics.record_success(latency_ms)
        else:
            _metrics.record_failure(result.error_message or "unknown", latency_ms)

        return result

    except HTTPException:
        raise
    except Exception as e:
        latency_ms = int((time.time() - req_start) * 1000)
        _metrics.record_failure(type(e).__name__, latency_ms)
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
        "version": "1.2.0",
    }


@app.get("/metrics")
async def metrics():
    """Return request metrics (counts, latency, errors)."""
    return _metrics.snapshot()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8091)
