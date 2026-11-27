"""Export teacher utterances with weak labels from ``m3.session_texts`` / ``m3.events``.

Two label sources are used:

1. ``teacher.reply`` events carry the ``text_id`` and the accepted intent; these
   are strong action labels produced by the state machine.
2. Teacher button events (``teacher.called_on``, ``teacher.marked``, ...) within
   a short window after the text are weak labels.

Events are matched by session and time. The output JSONL is what
``train.py`` consumes; it deliberately keeps ``source`` so the trainer and the
evaluation report can separate action labels from weak labels.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

EVENT_LABELS = {
    "teacher.called_on": "call_on",
    "classroom.question_asked": "ask_class",
    "teacher.marked": "comment_answer",
    "teacher.management_action": "manage_discipline",
    "dialogue.ended": "end_dialogue",
    "classroom.qa_ended": "end_qa",
}
REPLY_INTENTS = {
    "ask_class",
    "ask_student",
    "call_on",
    "prompt_student_question",
    "manage_discipline",
    "comment_answer",
    "end_dialogue",
    "end_qa",
    "other",
}


def _payload(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, (str, bytes)):
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, dict) else {}
        except ValueError:
            return {}
    return {}


def _polarity(event: dict[str, Any]) -> str | None:
    correctness = _payload(event.get("payload")).get("correctness")
    if correctness == "correct":
        return "positive"
    if correctness == "wrong":
        return "negative"
    if correctness == "partial":
        return "neutral"
    return None


def label_text_rows(
    texts: list[dict[str, Any]],
    events: list[dict[str, Any]],
    names_by_session_student: dict[tuple[str, str], str] | None = None,
    *,
    window_ms: int = 3000,
) -> list[dict[str, Any]]:
    """Pure labeling function (used by tests and the DB exporter).

    ``texts`` rows: ``id``, ``session_id``, ``text``, ``end_ms``, ``at_ms``.
    ``events`` rows: ``session_id``, ``seq``, ``type``, ``timestamp_ms``,
    ``payload``.
    """
    by_session: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        by_session[str(event["session_id"])].append(event)
    for session_events in by_session.values():
        session_events.sort(key=lambda item: (int(item["timestamp_ms"]), int(item["seq"])))

    names = names_by_session_student or {}
    texts_by_session: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for text in texts:
        texts_by_session[str(text["session_id"])].append(text)
    next_anchor_by_id: dict[str, int | None] = {}
    for session_texts in texts_by_session.values():
        ordered = sorted(
            session_texts,
            key=lambda item: int(item.get("end_ms") or item.get("at_ms") or 0),
        )
        for position, item in enumerate(ordered):
            next_anchor_by_id[str(item["id"])] = (
                int(ordered[position + 1].get("end_ms") or ordered[position + 1].get("at_ms") or 0)
                if position + 1 < len(ordered)
                else None
            )

    records: list[dict[str, Any]] = []
    for index, text in enumerate(texts):
        text_id = str(text["id"])
        session_id = str(text["session_id"])
        session_events = by_session.get(session_id, [])

        reply = next(
            (
                event
                for event in session_events
                if event["type"] == "teacher.reply"
                and str(_payload(event.get("payload")).get("text_id")) == text_id
            ),
            None,
        )
        labels: list[str] = []
        student_name: str | None = None
        polarity: str | None = None
        source = "none"
        if reply is not None:
            intent = _payload(reply.get("payload")).get("intent")
            if intent in REPLY_INTENTS:
                labels = [intent]
                source = "action"
                student_name = _payload(reply.get("payload")).get("student_name")
                polarity = _payload(reply.get("payload")).get("polarity")
        else:
            anchor = text.get("end_ms")
            if anchor is None:
                anchor = text.get("at_ms", 0)
            anchor = int(anchor)
            window_end = anchor + window_ms
            next_anchor = next_anchor_by_id.get(text_id)
            if next_anchor is not None and next_anchor > anchor:
                # An event after the next teacher utterance belongs to that
                # utterance, not to this one.
                window_end = min(window_end, next_anchor)
            candidates = [
                event
                for event in session_events
                if anchor <= int(event["timestamp_ms"]) < window_end
                and event["type"] in EVENT_LABELS
            ]
            for event in candidates:
                label = EVENT_LABELS[event["type"]]
                payload = _payload(event.get("payload"))
                if event["type"] == "classroom.question_asked":
                    if payload.get("intent") == "student_question":
                        label = "prompt_student_question"
                if event["type"] == "dialogue.ended" and payload.get("reason") != "teacher":
                    continue
                if label not in labels:
                    labels.append(label)
                if event["type"] == "teacher.called_on":
                    student_id = str(payload.get("student_id"))
                    student_name = names.get((session_id, student_id))
                if event["type"] == "teacher.marked":
                    polarity = _polarity(event)
            if labels:
                source = "weak"
        if not labels:
            labels = ["other"]

        records.append(
            {
                "id": text_id,
                "text": str(text.get("text") or ""),
                "labels": labels,
                "student_name": student_name,
                "polarity": polarity,
                "group": session_id,
                "source": source,
                "at_ms": text.get("at_ms"),
                "candidate_index": index,
            }
        )
    return records


def fetch_rows(
    dsn: str,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[tuple[str, str], str]]:
    try:
        import psycopg
        from psycopg.rows import dict_row
    except ImportError as exc:  # pragma: no cover - exercised only by the CLI
        raise SystemExit(
            "psycopg is required for the DB exporter: "
            "pip install -r ai/training/teacher_intent/requirements.txt"
        ) from exc

    with psycopg.connect(dsn, row_factory=dict_row) as connection:
        texts = connection.execute(
            """
            SELECT id, session_id, text, start_ms, end_ms, at_ms
            FROM m3_classroom.session_texts
            WHERE speaker_type = 'teacher'
              AND source = 'asr'
              AND kind = 'utterance'
              AND status = 'final'
            ORDER BY session_id, at_ms, id
            """
        ).fetchall()
        events = connection.execute(
            """
            SELECT session_id, seq, type, timestamp_ms, payload
            FROM m3_classroom.events
            WHERE type IN (
                'teacher.reply',
                'teacher.called_on',
                'teacher.marked',
                'teacher.management_action',
                'classroom.question_asked',
                'classroom.qa_ended',
                'dialogue.ended'
            )
            ORDER BY session_id, seq
            """
        ).fetchall()
        names = connection.execute(
            """
            SELECT s.id AS session_id, vs.id AS student_id, vs.name
            FROM m3_classroom.sessions s
            JOIN m3_classroom.virtual_students vs ON vs.task_id = s.task_id
            """
        ).fetchall()

    name_map = {
        (str(row["session_id"]), str(row["student_id"])): str(row["name"])
        for row in names
    }
    return list(texts), list(events), name_map


def write_jsonl(records: Iterable[dict[str, Any]], path: Path) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
            count += 1
    return count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dsn", required=True, help="PostgreSQL DSN")
    parser.add_argument("--out", default="data/weak.jsonl")
    parser.add_argument("--window-ms", type=int, default=3000)
    args = parser.parse_args()

    texts, events, names = fetch_rows(args.dsn)
    records = label_text_rows(texts, events, names, window_ms=args.window_ms)
    count = write_jsonl(records, Path(args.out))
    action = sum(1 for record in records if record["source"] == "action")
    weak = sum(1 for record in records if record["source"] == "weak")
    print(f"wrote {count} records ({action} action / {weak} weak) to {args.out}")


if __name__ == "__main__":
    main()
