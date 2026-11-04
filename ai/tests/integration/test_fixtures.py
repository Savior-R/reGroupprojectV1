import json
import struct
import wave
from pathlib import Path

FIXTURES = Path(__file__).resolve().parents[1] / "fixtures"


def test_classroom_requests_fixture_has_required_fields():
    data = json.loads((FIXTURES / "classroom_requests.json").read_text(encoding="utf-8"))

    assert set(data) >= {"context", "question", "answer", "classify"}
    assert data["context"]["session_id"] == "fixture-session"
    assert data["context"]["student"]["student_id"] == "s1"
    assert data["question"]["question_kind"] == "student_question"
    assert data["answer"]["question"]
    assert data["classify"]["text"]


def test_expected_fixture_has_assertion_values():
    data = json.loads((FIXTURES / "expected.json").read_text(encoding="utf-8"))

    assert data["ocr_text_contains"] == "Hello OCR 123"
    assert "阿里巴巴语音实验室" in data["asr_final_text_contains"]


def test_ocr_image_fixture_is_png():
    data = (FIXTURES / "images" / "ocr_sample.png").read_bytes()

    assert data.startswith(b"\x89PNG\r\n\x1a\n")
    width, height = struct.unpack(">II", data[16:24])
    assert (width, height) == (520, 140)


def test_asr_audio_fixture_is_16khz_mono_pcm16():
    path = FIXTURES / "audio" / "asr_sample.wav"

    with wave.open(str(path), "rb") as wav:
        assert wav.getnchannels() == 1
        assert wav.getsampwidth() == 2
        assert wav.getframerate() == 16000
        duration = wav.getnframes() / wav.getframerate()
        assert 3.0 <= duration <= 5.0
