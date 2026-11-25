"""Turn a JSONL dataset into an Excel/LibreOffice-friendly annotation sheet.

The pre-filled label columns come from the weak / synthetic labels. Annotators
only need to correct 0/1 values and add notes; ``merge_annotations.py`` turns
the filled sheet back into training JSONL.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path
from typing import Any, Iterable

from .labels import LABELS

BASE_COLUMNS = ["id", "text", "group", "source", "suggested", "student_name", "polarity"]
NOTE_COLUMN = "notes"


def annotation_columns() -> list[str]:
    return BASE_COLUMNS + list(LABELS) + [NOTE_COLUMN]


def build_annotation_rows(records: Iterable[dict[str, Any]]) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for record in records:
        labels = set(record.get("labels") or [])
        row = {
            "id": str(record.get("id", "")),
            "text": str(record.get("text", "")),
            "group": str(record.get("group", "")),
            "source": str(record.get("source", "")),
            "suggested": ";".join(label for label in LABELS if label in labels),
            "student_name": str(record.get("student_name") or ""),
            "polarity": str(record.get("polarity") or ""),
            "notes": "",
        }
        for label in LABELS:
            row[label] = "1" if label in labels else "0"
        rows.append(row)
    return rows


def write_csv(rows: list[dict[str, str]], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=annotation_columns())
        writer.writeheader()
        writer.writerows(rows)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    records = []
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--limit", type=int, default=0)
    args = parser.parse_args()

    records = read_jsonl(args.input)
    if args.limit > 0:
        records = records[: args.limit]
    write_csv(build_annotation_rows(records), args.out)
    print(f"wrote {len(records)} annotation rows to {args.out}")
    print("columns:", ", ".join(annotation_columns()))


if __name__ == "__main__":
    main()
