from __future__ import annotations

import json

from app.schemas.classroom import ClassroomContext

PROMPT_VERSION = "m3-classroom-v1"

SYSTEM_PROMPT = """你是师范生试讲训练平台里的虚拟学生。
根据课堂上下文，以学生口吻向老师提出一个自然、具体、与当前教学内容相关的问题。
只输出 JSON，不要输出 Markdown 或解释。JSON 字段：
- text: 学生问题，1—2 句中文
- context_sufficient: 上下文是否足以生成问题
- input_question_complete: 教师当前问题是否完整
- output_complete: 生成是否完整
- missing: 缺少的上下文字段数组
- confidence: 0—1
"""

ANSWER_SYSTEM_PROMPT = """你是师范生试讲训练平台里的虚拟学生。
根据老师的提问和课堂上下文，以学生口吻给出简短回答。
只输出 JSON，不要输出 Markdown 或解释。JSON 字段：
- text: 学生回答，1—3 句中文
- quality: correct / partial / incorrect
- confidence: 0—1
"""

CLASSIFY_SYSTEM_PROMPT = """你负责判断教师话语是否构成一个课堂提问。
只输出 JSON，不要输出 Markdown 或解释。JSON 字段：
- label: question / not_question
- confidence: 0—1
"""


def question_messages(context: ClassroomContext) -> list[dict[str, str]]:
    payload = {
        "topic": context.topic,
        "mode": context.mode,
        "recent_texts": context.recent_texts,
        "screen_text": context.screen_text,
        "scenario": context.scenario,
        "student": context.student.model_dump(),
    }
    return [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": "课堂上下文：\n"
            + json.dumps(payload, ensure_ascii=False, indent=2)
            + "\n请生成这个学生此刻最可能问的问题。",
        },
    ]


def answer_messages(
    context: ClassroomContext,
    question: str,
    teacher_feedback: str | None = None,
) -> list[dict[str, str]]:
    payload = {
        "topic": context.topic,
        "mode": context.mode,
        "recent_texts": context.recent_texts,
        "screen_text": context.screen_text,
        "student": context.student.model_dump(),
        "question": question,
        "teacher_feedback": teacher_feedback,
    }
    return [
        {"role": "system", "content": ANSWER_SYSTEM_PROMPT},
        {
            "role": "user",
            "content": "课堂上下文与问题：\n"
            + json.dumps(payload, ensure_ascii=False, indent=2)
            + "\n请以该学生口吻回答，并判断回答质量。",
        },
    ]


def classify_messages(text: str) -> list[dict[str, str]]:
    return [
        {"role": "system", "content": CLASSIFY_SYSTEM_PROMPT},
        {
            "role": "user",
            "content": "教师话语：\n" + text + "\n判断这句话是否在向全班或某个学生提问。",
        },
    ]
