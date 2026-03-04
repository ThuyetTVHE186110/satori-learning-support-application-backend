"""
Unit tests for Japanese Pronunciation Assessment Microservice.

Run with:  pip install -r requirements-test.txt && pytest test_main.py -v
"""

import io
import time
from unittest.mock import patch, MagicMock

import numpy as np
import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport

from main import (
    app,
    contains_japanese,
    split_katakana_to_mora,
    classify_mora,
    get_moras_from_text,
    score_pitch_accent,
    estimate_pitch_pattern_for_moras,
    estimate_mora_boundaries,
    analyze_mora_timing,
    compute_special_mora_accuracy,
    MoraDetail,
    Metrics,
    _accent_cache,
    _cache_get,
    _cache_put,
    ACCENT_CACHE_TTL,
)


# ─── Helper: generate synthetic WAV bytes ────────────────────────────────────

def _make_wav_bytes(duration: float = 1.0, sr: int = 16000, freq: float = 440.0) -> bytes:
    """Generate a simple sine wave WAV in-memory."""
    import soundfile as sf

    t = np.linspace(0, duration, int(sr * duration), endpoint=False)
    audio = (0.5 * np.sin(2 * np.pi * freq * t)).astype(np.float32)
    buf = io.BytesIO()
    sf.write(buf, audio, sr, format="WAV")
    buf.seek(0)
    return buf.read()


# ─── contains_japanese ───────────────────────────────────────────────────────

class TestContainsJapanese:
    def test_hiragana(self):
        assert contains_japanese("こんにちは") is True

    def test_katakana(self):
        assert contains_japanese("カタカナ") is True

    def test_kanji(self):
        assert contains_japanese("漢字") is True

    def test_mixed(self):
        assert contains_japanese("Hello 世界") is True

    def test_ascii_only(self):
        assert contains_japanese("Hello World") is False

    def test_empty(self):
        assert contains_japanese("") is False

    def test_korean(self):
        assert contains_japanese("안녕하세요") is False

    def test_numbers(self):
        assert contains_japanese("12345") is False


# ─── split_katakana_to_mora ──────────────────────────────────────────────────

class TestSplitKatakanaMora:
    def test_simple(self):
        assert split_katakana_to_mora("アイウ") == ["ア", "イ", "ウ"]

    def test_compound_mora(self):
        # シャ = シ + small ャ → compound mora
        assert split_katakana_to_mora("シャ") == ["シャ"]

    def test_mixed(self):
        # キョウト = キョ + ウ + ト
        assert split_katakana_to_mora("キョウト") == ["キョ", "ウ", "ト"]

    def test_sokuon(self):
        # ニッポン = ニ + ッ + ポ + ン
        assert split_katakana_to_mora("ニッポン") == ["ニ", "ッ", "ポ", "ン"]

    def test_long_vowel(self):
        # トーキョー = ト + ー + キョ + ー
        assert split_katakana_to_mora("トーキョー") == ["ト", "ー", "キョ", "ー"]

    def test_empty(self):
        assert split_katakana_to_mora("") == []

    def test_single_char(self):
        assert split_katakana_to_mora("ア") == ["ア"]


# ─── classify_mora ───────────────────────────────────────────────────────────

class TestClassifyMora:
    def test_regular(self):
        assert classify_mora("ア") == "regular"
        assert classify_mora("カ") == "regular"
        assert classify_mora("シャ") == "regular"

    def test_sokuon(self):
        assert classify_mora("ッ") == "sokuon"

    def test_hatsuon(self):
        assert classify_mora("ン") == "hatsuon"

    def test_long_vowel(self):
        assert classify_mora("ー") == "long_vowel"


# ─── get_moras_from_text ────────────────────────────────────────────────────

class TestGetMorasFromText:
    def test_hiragana_input(self):
        # Direct kana splitting (hiragana converted to katakana)
        with patch("main.pyopenjtalk", None):
            moras = get_moras_from_text("あいう")
            assert moras == ["ア", "イ", "ウ"]

    def test_katakana_input(self):
        with patch("main.pyopenjtalk", None):
            moras = get_moras_from_text("カタカナ")
            assert moras == ["カ", "タ", "カ", "ナ"]

    def test_kanji_only_no_fallback(self):
        with patch("main.pyopenjtalk", None):
            moras = get_moras_from_text("漢字")
            assert moras == []

    def test_empty(self):
        with patch("main.pyopenjtalk", None):
            moras = get_moras_from_text("")
            assert moras == []


# ─── score_pitch_accent ─────────────────────────────────────────────────────

class TestScorePitchAccent:
    def test_perfect_match(self):
        is_correct, score = score_pitch_accent(["L", "H", "H"], ["L", "H", "H"])
        assert is_correct is True
        assert score == 100.0

    def test_all_wrong(self):
        is_correct, score = score_pitch_accent(["L", "H", "H"], ["H", "L", "L"])
        assert is_correct is False
        assert score == 0.0

    def test_partial_match(self):
        # 2 of 4 correct = 50%
        is_correct, score = score_pitch_accent(
            ["L", "H", "H", "L"], ["L", "H", "L", "H"]
        )
        assert is_correct is False
        assert score == 50.0

    def test_unvoiced_skipped(self):
        # "?" segments should be ignored
        is_correct, score = score_pitch_accent(["L", "H"], ["?", "H"])
        assert is_correct is True
        assert score == 100.0

    def test_all_unvoiced(self):
        is_correct, score = score_pitch_accent(["L", "H"], ["?", "?"])
        assert is_correct is True
        assert score == 100.0

    def test_empty_expected(self):
        is_correct, score = score_pitch_accent([], ["H", "L"])
        assert is_correct is True
        assert score == 100.0

    def test_empty_detected(self):
        is_correct, score = score_pitch_accent(["H", "L"], [])
        assert is_correct is True
        assert score == 100.0

    def test_threshold_boundary(self):
        # 4 out of 5 = 80% → exactly at threshold → correct
        is_correct, score = score_pitch_accent(
            ["L", "H", "H", "H", "L"],
            ["L", "H", "H", "H", "H"],  # 1 wrong
        )
        assert is_correct is True
        assert score == 80.0


# ─── estimate_pitch_pattern_for_moras ────────────────────────────────────────

class TestEstimatePitchPattern:
    def test_empty_moras(self):
        f0 = np.array([200.0, 210.0, 190.0])
        result = estimate_pitch_pattern_for_moras(f0, 16000, 256, [], [0.0])
        assert result == []

    def test_all_nan_f0(self):
        f0 = np.array([np.nan, np.nan, np.nan])
        result = estimate_pitch_pattern_for_moras(
            f0, 16000, 256, ["ア", "イ"], [0.0, 0.5, 1.0]
        )
        assert result == ["?", "?"]

    def test_returns_correct_length(self):
        # 100 frames of varying pitch
        f0 = np.concatenate([
            np.full(50, 250.0),  # high
            np.full(50, 150.0),  # low
        ])
        boundaries = [0.0, 0.5, 1.0]
        result = estimate_pitch_pattern_for_moras(
            f0, 16000, 256, ["ア", "イ"], boundaries
        )
        assert len(result) == 2
        assert all(p in ("H", "L", "?") for p in result)


# ─── estimate_mora_boundaries ────────────────────────────────────────────────

class TestEstimateMoraBoundaries:
    def test_single_mora(self):
        audio = np.zeros(16000)  # 1 second
        boundaries = estimate_mora_boundaries(audio, 16000, 1)
        assert len(boundaries) == 2
        assert boundaries[0] == 0.0
        assert boundaries[1] == pytest.approx(1.0)

    def test_equal_fallback(self):
        # Silent audio → no onsets → equal division
        audio = np.zeros(16000 * 2)  # 2 seconds
        boundaries = estimate_mora_boundaries(audio, 16000, 4)
        assert len(boundaries) == 5
        assert boundaries[0] == 0.0
        assert boundaries[-1] == pytest.approx(2.0)
        # Check approximately equal spacing
        for i in range(4):
            assert boundaries[i + 1] - boundaries[i] == pytest.approx(0.5, abs=0.01)

    def test_strictly_increasing(self):
        # Any audio should produce strictly increasing boundaries
        np.random.seed(42)
        audio = np.random.randn(16000).astype(np.float32)
        boundaries = estimate_mora_boundaries(audio, 16000, 5)
        assert len(boundaries) == 6
        for i in range(len(boundaries) - 1):
            assert boundaries[i] < boundaries[i + 1]


# ─── analyze_mora_timing ────────────────────────────────────────────────────

class TestAnalyzeMoraTiming:
    def test_empty_moras(self):
        audio = np.zeros(16000)
        details, score = analyze_mora_timing(audio, 16000, [], [0.0])
        assert details == []
        assert score == 100.0

    def test_returns_correct_count(self):
        np.random.seed(42)
        audio = np.random.randn(16000).astype(np.float32) * 0.1
        moras = ["ア", "イ", "ウ"]
        boundaries = [0.0, 1 / 3, 2 / 3, 1.0]
        details, score = analyze_mora_timing(audio, 16000, moras, boundaries)
        assert len(details) == 3
        assert all(isinstance(d, MoraDetail) for d in details)
        assert 0 <= score <= 100

    def test_mora_types_classified(self):
        np.random.seed(42)
        audio = np.random.randn(16000).astype(np.float32) * 0.1
        moras = ["ア", "ッ", "ン", "ー"]
        boundaries = [0.0, 0.25, 0.5, 0.75, 1.0]
        details, _ = analyze_mora_timing(audio, 16000, moras, boundaries)
        types = [d.mora_type for d in details]
        assert types == ["regular", "sokuon", "hatsuon", "long_vowel"]


# ─── compute_special_mora_accuracy ───────────────────────────────────────────

class TestSpecialMoraAccuracy:
    def test_all_good(self):
        details = [
            MoraDetail(mora="ッ", expected_relative_duration=1.0,
                       actual_relative_duration=1.0, status="good", mora_type="sokuon"),
            MoraDetail(mora="ン", expected_relative_duration=1.0,
                       actual_relative_duration=1.0, status="good", mora_type="hatsuon"),
        ]
        result = compute_special_mora_accuracy(details)
        assert result["sokuon_accuracy"] == 100.0
        assert result["hatsuon_accuracy"] == 100.0
        assert result["long_vowel_accuracy"] is None

    def test_mixed(self):
        details = [
            MoraDetail(mora="ッ", expected_relative_duration=1.0,
                       actual_relative_duration=0.3, status="too_short", mora_type="sokuon"),
            MoraDetail(mora="ッ", expected_relative_duration=1.0,
                       actual_relative_duration=1.0, status="good", mora_type="sokuon"),
        ]
        result = compute_special_mora_accuracy(details)
        assert result["sokuon_accuracy"] == 50.0

    def test_no_special_moras(self):
        details = [
            MoraDetail(mora="ア", expected_relative_duration=1.0,
                       actual_relative_duration=1.0, status="good", mora_type="regular"),
        ]
        result = compute_special_mora_accuracy(details)
        assert result["sokuon_accuracy"] is None
        assert result["hatsuon_accuracy"] is None
        assert result["long_vowel_accuracy"] is None


# ─── Metrics ────────────────────────────────────────────────────────────────

class TestMetrics:
    def test_initial_state(self):
        m = Metrics()
        snap = m.snapshot()
        assert snap["total_requests"] == 0
        assert snap["successful"] == 0
        assert snap["avg_latency_ms"] == 0

    def test_record_success(self):
        m = Metrics()
        m.record_success(100)
        m.record_success(200)
        snap = m.snapshot()
        assert snap["total_requests"] == 2
        assert snap["successful"] == 2
        assert snap["avg_latency_ms"] == 150.0

    def test_record_failure(self):
        m = Metrics()
        m.record_failure("timeout", 50)
        m.record_failure("timeout", 60)
        m.record_failure("decode_error", 30)
        snap = m.snapshot()
        assert snap["total_requests"] == 3
        assert snap["failed"] == 3
        assert snap["errors_by_type"]["timeout"] == 2
        assert snap["errors_by_type"]["decode_error"] == 1

    def test_record_timeout(self):
        m = Metrics()
        m.record_timeout()
        snap = m.snapshot()
        assert snap["timeouts"] == 1
        assert snap["total_requests"] == 1


# ─── Cache TTL ──────────────────────────────────────────────────────────────

class TestCacheTTL:
    def setup_method(self):
        _accent_cache.clear()

    def test_cache_hit(self):
        _cache_put("テスト", [{"word": "テスト"}])
        result = _cache_get("テスト")
        assert result is not None
        assert result[0]["word"] == "テスト"

    def test_cache_miss(self):
        result = _cache_get("missing")
        assert result is None

    def test_cache_expiry(self):
        # Insert entry with old timestamp
        _accent_cache["old"] = (time.time() - ACCENT_CACHE_TTL - 1, [{"word": "old"}])
        result = _cache_get("old")
        assert result is None
        assert "old" not in _accent_cache

    def test_cache_eviction(self):
        # Fill cache to max, then add one more
        for i in range(256):
            _cache_put(f"key_{i}", [])
        assert len(_accent_cache) == 256
        _cache_put("new_key", [{"word": "new"}])
        assert len(_accent_cache) == 256
        assert "new_key" in _accent_cache

    def teardown_method(self):
        _accent_cache.clear()


# ─── HTTP Endpoint Tests ────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_health_endpoint():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert "status" in data
    assert data["version"] == "1.2.0"


@pytest.mark.asyncio
async def test_metrics_endpoint():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/metrics")
    assert resp.status_code == 200
    data = resp.json()
    assert "total_requests" in data
    assert "avg_latency_ms" in data


@pytest.mark.asyncio
async def test_assess_empty_audio():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/assess",
            files={"audio": ("test.wav", b"", "audio/wav")},
            data={"reference_text": "テスト"},
        )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_assess_empty_text():
    wav_bytes = _make_wav_bytes(duration=1.0)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/assess",
            files={"audio": ("test.wav", wav_bytes, "audio/wav")},
            data={"reference_text": "   "},
        )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_assess_non_japanese_text():
    wav_bytes = _make_wav_bytes(duration=1.0)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/assess",
            files={"audio": ("test.wav", wav_bytes, "audio/wav")},
            data={"reference_text": "Hello World"},
        )
    assert resp.status_code == 400
    assert "Japanese" in resp.json()["detail"]


@pytest.mark.asyncio
async def test_assess_success_with_synthetic_audio():
    """Integration test: valid audio + Japanese text should return success."""
    wav_bytes = _make_wav_bytes(duration=1.0, freq=250.0)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post(
            "/assess",
            files={"audio": ("test.wav", wav_bytes, "audio/wav")},
            data={"reference_text": "テスト"},
        )
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is True
    assert "pitch_accent_score" in data
    assert "mora_timing_score" in data
    assert data["detailed_feedback"] is not None
