from __future__ import annotations

import json
from pathlib import Path

import pytest

from training.teacher_intent.annotate import build_annotation_rows
from training.teacher_intent.export_weak_labels import label_text_rows
from training.teacher_intent.labels import LABELS
from training.teacher_intent.merge_annotations import merge_rows
from training.teacher_intent.seed_data import build_records
from training.teacher_intent.text import prepare


def test_seed_records_cover_every_label_and_multi_label() -> None:
    records = build_records()

    seen = {label for record in records for label in record["labels"]}
    assert seen == set(LABELS)
    assert all(set(record["labels"]) <= set(LABELS) for record in records)
    assert any(len(record["labels"]) > 1 for record in records)
    assert len({record["id"] for record in records}) == len(records)


def test_prepare_masks_names_and_punctuation() -> None:
    assert prepare("张三，请坐", ["张三", "李四"]) == "某同学请坐"
    assert prepare("同学们，光合作用需要什么条件？") == "同学们光合作用需要什么条件"
    assert prepare("张三他爸来了", ["张三"]) == "某同学他爸来了"


def test_weak_label_exporter_uses_reply_then_button_then_other() -> None:
    texts = [
        {"id": "t1", "session_id": "s1", "text": "请坐", "end_ms": 1000, "at_ms": 1000},
        {"id": "t2", "session_id": "s1", "text": "张三你来说", "end_ms": 2000, "at_ms": 2000},
        {"id": "t3", "session_id": "s1", "text": "我们看这一段", "end_ms": 3000, "at_ms": 3000},
        {"id": "t4", "session_id": "s1", "text": "很好", "end_ms": 4000, "at_ms": 4000},
    ]
    events = [
        {
            "session_id": "s1",
            "seq": 1,
            "type": "teacher.reply",
            "timestamp_ms": 1000,
            "payload": {"text_id": "t1", "intent": "end_dialogue"},
        },
        {
            "session_id": "s1",
            "seq": 2,
            "type": "teacher.called_on",
            "timestamp_ms": 2200,
            "payload": {"student_id": "stu-1"},
        },
        {
            "session_id": "s1",
            "seq": 3,
            "type": "teacher.marked",
            "timestamp_ms": 4300,
            "payload": {"correctness": "correct"},
        },
    ]
    records = label_text_rows(
        texts, events, {("s1", "stu-1"): "张三"}
    )

    by_id = {record["id"]: record for record in records}
    assert by_id["t1"]["labels"] == ["end_dialogue"]
    assert by_id["t1"]["source"] == "action"
    assert by_id["t2"]["labels"] == ["call_on"]
    assert by_id["t2"]["source"] == "weak"
    assert by_id["t2"]["student_name"] == "张三"
    assert by_id["t3"]["labels"] == ["other"]
    assert by_id["t3"]["source"] == "none"
    assert by_id["t4"]["labels"] == ["comment_answer"]
    assert by_id["t4"]["polarity"] == "positive"


def test_annotation_sheet_roundtrip_and_skips_unfinished_rows() -> None:
    rows = build_annotation_rows(
        [
            {
                "id": "t1",
                "text": "很好，请坐",
                "labels": ["comment_answer", "end_dialogue"],
                "group": "s1",
                "source": "human",
                "student_name": "张三",
                "polarity": "positive",
            },
            {
                "id": "t2",
                "text": "我们看这一段",
                "labels": ["other"],
                "group": "s1",
                "source": "human",
            },
        ]
    )
    assert rows[0]["comment_answer"] == "1"
    assert rows[0]["end_dialogue"] == "1"
    assert rows[0]["ask_class"] == "0"

    rows[1]["other"] = "0"  # simulate an unfinished row
    records, skipped = merge_rows(rows)

    assert len(records) == 1
    assert skipped == 1
    assert records[0]["labels"] == ["comment_answer", "end_dialogue"]
    assert records[0]["student_name"] == "张三"
    assert records[0]["polarity"] == "positive"
    assert records[0]["source"] == "human"


def test_exported_linear_model_matches_sklearn_transform(tmp_path: Path) -> None:
    np = pytest.importorskip("numpy")
    pytest.importorskip("sklearn")
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression

    from training.teacher_intent.infer import LinearTeacherIntentModel, sigmoid
    from training.teacher_intent.train import export_model, label_matrix, prepare_texts

    records = build_records()[:120]
    texts = prepare_texts(records)
    matrix = label_matrix(records)
    vectorizer = TfidfVectorizer(
        analyzer="char",
        ngram_range=(1, 3),
        min_df=1,
        max_features=2000,
        lowercase=False,
        norm="l2",
        sublinear_tf=False,
        smooth_idf=True,
    )
    features = vectorizer.fit_transform(texts)
    weights: list = []
    intercepts: list[float] = []
    for position in range(len(LABELS)):
        targets = matrix[:, position]
        if int(targets.sum()) == 0:
            weights.append(np.zeros(features.shape[1]))
            intercepts.append(-10.0)
            continue
        model = LogisticRegression(
            C=4.0, class_weight="balanced", max_iter=2000, solver="liblinear"
        )
        model.fit(features, targets)
        weights.append(np.asarray(model.coef_[0]))
        intercepts.append(float(model.intercept_[0]))

    model_path = tmp_path / "model.json"
    model_path.write_text(
        json.dumps(
            export_model(
                vectorizer,
                weights,
                intercepts,
                metrics={},
                training_size=len(texts),
            )
        ),
        encoding="utf-8",
    )
    exported = LinearTeacherIntentModel.load(model_path)

    for text in [
        "张三，你来说说光合作用需要什么条件？",
        "很好，请坐",
        "我们继续讲下一部分",
        "同学们，光合作用需要什么条件？",
    ]:
        prepared = prepare(text, ["张三", "李四"])
        vector = vectorizer.transform([prepared])
        expected = {}
        for position, label in enumerate(LABELS):
            expected[label] = sigmoid(
                float((vector @ weights[position]).item()) + intercepts[position]
            )
        actual = exported.scores(text, ["张三", "李四"])
        for label in LABELS:
            assert actual[label] == pytest.approx(expected[label], abs=1e-6)


def test_committed_model_artifact_loads_and_predicts() -> None:
    model_path = (
        Path(__file__).resolve().parents[2]
        / "models"
        / "teacher_intent"
        / "v1"
        / "model.json"
    )
    if not model_path.exists():
        pytest.skip("baseline model artifact not generated")
    from training.teacher_intent.infer import LinearTeacherIntentModel

    model = LinearTeacherIntentModel.load(model_path)
    result = model.predict("很好，请坐", ["张三"])
    labels = {hit["label"] for hit in result["intents"]}
    assert "end_dialogue" in labels
    assert result["model_version"] == "teacher-intent-linear-v1"
