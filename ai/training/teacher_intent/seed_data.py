"""Deterministic synthetic seed corpus for the first linear baseline.

The seed corpus is intentionally small and template-based: it lets the data
pipeline, training, export and inference be exercised before any real classroom
recordings are available. It is *not* a substitute for the real ASR golden set.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

NAMES = ["张三", "李四", "王小明", "林小雨", "陈晨", "赵敏", "周杰", "刘洋"]
QUESTIONS = [
    "光合作用需要什么条件",
    "这段话的中心思想是什么",
    "作者为什么这样写",
    "这个公式怎么推导",
    "谁能说说自己的理解",
    "这篇文章的线索是什么",
    "我们为什么要保护环境",
    "这个实验说明了什么",
]
LECTURE_PHRASES = [
    "我们看这一段",
    "打开课本第五页",
    "这个字的写法要注意",
    "大家把笔记记一下",
    "我们来看作者的背景",
    "注意这个公式的推导",
    "这一段的环境描写很细",
    "同学们看屏幕",
    "这个知识点考试经常出现",
    "我们先把概念理清楚",
]

Record = dict[str, Any]


def _add(
    records: list[Record],
    text: str,
    labels: list[str],
    group: str,
    *,
    student_name: str | None = None,
    polarity: str | None = None,
) -> None:
    records.append(
        {
            "id": f"seed-{len(records) + 1:04d}",
            "text": text,
            "labels": labels,
            "student_name": student_name,
            "polarity": polarity,
            "group": group,
            "source": "synthetic",
        }
    )


def build_records() -> list[Record]:
    records: list[Record] = []

    for index, question in enumerate(QUESTIONS):
        for template in range(4):
            text = [
                f"同学们，{question}？",
                f"大家想一想，{question}？",
                f"谁能回答：{question}？",
                f"我们来看，{question}？",
            ][template]
            _add(records, text, ["ask_class"], f"ask_class:{index}:{template}")

    for name in NAMES:
        for index, question in enumerate(QUESTIONS[:4]):
            for template in range(3):
                text = [
                    f"{name}，{question}？",
                    f"{name}，你来说说{question}？",
                    f"{name}，你来回答：{question}？",
                ][template]
                _add(
                    records,
                    text,
                    ["ask_student"],
                    f"ask_student:{index}:{template}",
                    student_name=name,
                )

    for name in NAMES:
        for template in range(4):
            text = [
                f"{name}，你来说",
                f"{name}，你来回答",
                f"{name}，你试试",
                f"{name}，你来说说看",
            ][template]
            _add(records, text, ["call_on"], f"call_on:{template}", student_name=name)

    for template in range(4):
        text = [
            "大家有什么问题吗？",
            "有问题可以举手",
            "谁还有疑问？",
            "同学们可以提问",
        ][template]
        _add(records, text, ["prompt_student_question"], f"prompt:{template}")

    for name in NAMES:
        for template in range(5):
            text = [
                f"{name}，别说话",
                f"{name}，注意听讲",
                f"{name}，坐好",
                f"{name}，不要玩手机",
                f"{name}，认真点",
            ][template]
            _add(
                records,
                text,
                ["manage_discipline"],
                f"manage_student:{template}",
                student_name=name,
            )
    for template, text in enumerate(["大家安静", "同学们安静一下", "注意听讲", "不要讲话"]):
        _add(records, text, ["manage_discipline"], f"manage_class:{template}")

    for template, (text, polarity) in enumerate(
        [
            ("很好", "positive"),
            ("不错", "positive"),
            ("回答得很对", "positive"),
            ("有道理", "positive"),
            ("说得好", "positive"),
            ("谢谢", "positive"),
            ("不对", "negative"),
            ("再想想", "negative"),
            ("回答不完整", "negative"),
            ("这个答案是错的", "negative"),
            ("嗯", "neutral"),
            ("好", "neutral"),
        ]
    ):
        _add(
            records,
            text,
            ["comment_answer"],
            f"comment:{template}",
            polarity=polarity,
        )

    for template, text in enumerate(
        ["请坐", "坐下吧", "你可以坐下了", "先坐下", "回到座位"]
    ):
        _add(records, text, ["end_dialogue"], f"end_dialogue:{template}")

    for template, text in enumerate(
        [
            "我们继续讲下一部分",
            "结束问答，开始讲课",
            "继续上课",
            "我们讲下一个知识点",
            "问答结束，看黑板",
        ]
    ):
        _add(records, text, ["end_qa"], f"end_qa:{template}")

    for template, text in enumerate(LECTURE_PHRASES):
        _add(records, text, ["other"], f"other:{template}")
    for index in range(20):
        _add(
            records,
            f"我们来看第{index + 1}个例子，注意这里的写法",
            ["other"],
            f"other_long:{index}",
        )

    # Multi-label utterances: these are the cases a single-label classifier
    # cannot represent.
    for template, (text, labels, polarity) in enumerate(
        [
            ("很好，请坐", ["comment_answer", "end_dialogue"], "positive"),
            ("不错，坐下吧", ["comment_answer", "end_dialogue"], "positive"),
            ("回答得很对，请坐", ["comment_answer", "end_dialogue"], "positive"),
            ("有道理，先坐下", ["comment_answer", "end_dialogue"], "positive"),
            ("很好，那其他同学呢？", ["comment_answer", "ask_class"], "positive"),
            ("不错，谁还有补充？", ["comment_answer", "ask_class"], "positive"),
            ("说得很好，大家还有问题吗？", ["comment_answer", "prompt_student_question"], "positive"),
        ]
    ):
        _add(
            records,
            text,
            labels,
            f"multi_comment:{template}",
            polarity=polarity,
        )

    for template in range(4):
        first = NAMES[template]
        second = NAMES[(template + 1) % len(NAMES)]
        text = [
            f"{first}别说话，{second}你来说",
            f"{first}注意听，{second}你来回答",
            f"{first}坐好，{second}你来说说看",
            f"{first}不要讲话，{second}你试试",
        ][template]
        _add(
            records,
            text,
            ["manage_discipline", "call_on"],
            f"multi_manage_call:{template}",
        )

    for name in NAMES[:4]:
        for index, question in enumerate(QUESTIONS[:2]):
            _add(
                records,
                f"{name}，你来说说{question}？",
                ["call_on", "ask_student"],
                f"multi_call_ask:{index}",
                student_name=name,
            )

    return records


def write_jsonl(path: Path) -> int:
    records = build_records()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    return len(records)


def main() -> None:
    path = Path(__file__).with_name("data") / "seed.jsonl"
    count = write_jsonl(path)
    print(f"wrote {count} records to {path}")


if __name__ == "__main__":
    main()
