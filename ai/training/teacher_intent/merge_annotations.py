"""Merge a filled annotation CSV into training JSONL.

Only rows with at least one label are kept. Set ``other=1`` explicitly for
lecture / transition utterances; a row with every label at 0 is treated as an
unfinished annotation and skipped.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

from .labels import LABELS

TRUE_VALUES = {"1", "true", "yes", "y", "是", "✓"}


def merge_rows(rows: list[dict[str, str]]) -> tuple[list[dict[str, object]], int]:
    records: list[dict[str, object]] = []
    skipped = 0
    for row in rows:
        text = (row.get("text") or "").strip()
        if not text:
            skipped += 1
            continue
        labels = [label for label in LABELS if (row.get(label) or "").strip().lower() in TRUE_VALUES]
        if not labels:
            skipped += 1
            continue
        records.append(
            {
                "id": (row.get("id") or "").strip() or f"human-{len(records) + 1:04d}",
                "text": text,
                "labels": labels,
                "student_name": (row.get("student_name") or "").strip() or None,
                "polarity": (row.get("polarity") or "").strip() or None,
                "group": (row.get("group") or row.get("id") or "").strip() or text,
                "source": "human",
                "notes": (row.get("notes") or "").strip(),
            }
        )
    return records, skipped


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()

    with args.annotations.open(encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    records, skipped = merge_rows(rows)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    print(f"wrote {len(records)} records to {args.out}; skipped {skipped} unfinished rows")


if __name__ == "__main__":
    main()
