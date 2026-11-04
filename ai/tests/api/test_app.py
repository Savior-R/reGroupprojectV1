from fastapi.testclient import TestClient

from app.config import Settings
from app.container import AppContainer
from app.main import create_app
from app.providers.base import ASRChunk
from app.schemas.classroom import ClassroomContentResponse
from app.schemas.ocr import OCRBox, OCRResponse


class FakeClassroom:
    async def question(self, request):
        return ClassroomContentResponse(
            text="模拟问题",
            context_sufficient=True,
            input_question_complete=True,
            output_complete=True,
            missing=[],
            confidence=0.8,
            model_version="mock",
            prompt_version="m3-test",
        )

    async def answer(self, request):
        return ClassroomContentResponse(
            text="模拟回答",
            context_sufficient=True,
            input_question_complete=True,
            output_complete=True,
            missing=[],
            confidence=0.7,
            quality="partial",
            model_version="mock",
            prompt_version="m3-test",
        )

    async def classify(self, request):
        from app.schemas.classroom import ClassroomClassifyResponse

        return ClassroomClassifyResponse(
            label="question",
            confidence=0.9,
            model_version="mock",
            prompt_version="m3-test",
        )


class FakeOCR:
    async def recognize(self, request):
        return OCRResponse(
            text="板书内容",
            boxes=[OCRBox(text="板书", x1=0, y1=0, x2=10, y2=10, confidence=0.9)],
            model_version="mock",
        )


class FakeASR:
    async def stream(self, audio, config):
        async for _ in audio:
            pass
        yield ASRChunk(text="同学", is_final=False, begin_ms=0, end_ms=100)
        yield ASRChunk(text="同学们好", is_final=True, begin_ms=0, end_ms=300)


def app():
    settings = Settings(service_token="test-token", ai_mock=True)
    return create_app(
        AppContainer(
            settings=settings,
            classroom=FakeClassroom(),
            ocr=FakeOCR(),
            asr=FakeASR(),
        )
    )


def question_payload():
    return {
        "context": {
            "session_id": "session-1",
            "task_id": "task-1",
            "mode": "free",
            "topic": "分数",
            "recent_texts": ["教师：看这个例子"],
            "student": {"student_id": "student-1", "name": "小明"},
        }
    }


def test_health_is_public():
    with TestClient(app()) as client:
        response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_classroom_requires_service_token():
    with TestClient(app()) as client:
        response = client.post("/internal/v1/classroom/question", json=question_payload())
    assert response.status_code == 401


def test_classroom_question_returns_flags():
    with TestClient(app()) as client:
        response = client.post(
            "/internal/v1/classroom/question",
            json=question_payload(),
            headers={"X-Service-Token": "test-token"},
        )
    assert response.status_code == 200
    body = response.json()
    assert body["text"] == "模拟问题"
    assert body["prompt_version"] == "m3-test"


def test_ocr_returns_text_and_boxes():
    with TestClient(app()) as client:
        response = client.post(
            "/internal/v1/ocr",
            json={"image_url": "https://example.com/frame.png"},
            headers={"X-Service-Token": "test-token"},
        )
    assert response.status_code == 200
    body = response.json()
    assert body["text"] == "板书内容"
    assert body["boxes"][0]["text"] == "板书"


def test_mock_mode_wires_real_capabilities_end_to_end():
    app = create_app(settings=Settings(ai_mock=True, service_token="test-token"))
    with TestClient(app) as client:
        response = client.post(
            "/internal/v1/classroom/question",
            json=question_payload(),
            headers={"X-Service-Token": "test-token"},
        )
    assert response.status_code == 200
    assert response.json()["text"]
    assert response.json()["model_version"] == "mock"


def test_asr_websocket_streams_chunks():
    with TestClient(app()) as client:
        with client.websocket_connect(
            "/internal/v1/asr/stream?session_id=session-1",
            headers={"X-Service-Token": "test-token"},
        ) as websocket:
            websocket.send_bytes(b"audio")
            websocket.send_text('{"type":"finish"}')
            first = websocket.receive_json()
            second = websocket.receive_json()
            done = websocket.receive_json()

    assert first["type"] == "chunk"
    assert first["text"] == "同学"
    assert second["is_final"] is True
    assert done == {"type": "done"}
