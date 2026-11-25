"""Label schema shared by the training pipeline and the exported model.

Keep this list in sync with the Java ``TeacherIntent`` enum. The order is part
of the model artifact and must not change without retraining.
"""

from __future__ import annotations

LABELS: tuple[str, ...] = (
    "ask_class",
    "ask_student",
    "call_on",
    "prompt_student_question",
    "manage_discipline",
    "comment_answer",
    "end_dialogue",
    "end_qa",
    "other",
)

LABEL_SET = frozenset(LABELS)

# Presentation / primary-intent precedence. Java resolves actions by its own
# thresholds; this order only decides ``primary_intent``.
PRECEDENCE: tuple[str, ...] = (
    "end_qa",
    "end_dialogue",
    "manage_discipline",
    "call_on",
    "prompt_student_question",
    "ask_student",
    "ask_class",
    "comment_answer",
    "other",
)

# Reference thresholds for evaluation and the future Python runtime. The Java
# actor has its own action thresholds; the model only exposes calibrated
# probabilities.
THRESHOLDS: dict[str, float] = {
    "ask_class": 0.75,
    "ask_student": 0.75,
    "call_on": 0.85,
    "prompt_student_question": 0.75,
    "manage_discipline": 0.85,
    "comment_answer": 0.60,
    "end_dialogue": 0.90,
    "end_qa": 0.90,
    "other": 0.0,
}

# Labels below this floor are omitted from the returned hit list, so a
# low-probability label can never be treated as present by the actor.
HIT_FLOOR = 0.25

MODEL_FORMAT = "linear-char-ngram-v1"
MODEL_VERSION = "teacher-intent-linear-v1"
