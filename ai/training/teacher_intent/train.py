"""Train the first multi-label teacher-intent linear baseline.

Pipeline:
1. load JSONL (seed corpus by default, plus optional weak/synthetic files),
2. normalize + mask student names,
3. char 1–3 gram TF-IDF,
4. one logistic regression per label (multi-label, independent sigmoid),
5. evaluate on a grouped holdout,
6. export a JSON artifact that the Java ``LinearTeacherIntentClassifier`` can
   load without Python, scikit-learn or numpy at runtime.

This script is offline-only. Production dependencies are unchanged.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
from collections import Counter
from pathlib import Path
from typing import Any

from .labels import LABELS, PRECEDENCE, THRESHOLDS, MODEL_FORMAT, MODEL_VERSION
from .seed_data import NAMES, build_records
from .text import prepare

try:  # imported lazily so the rest of the repo does not require sklearn
    import numpy as np
    from sklearn.feature_extraction.text import TfidfVectorizer
    from sklearn.linear_model import LogisticRegression
    from sklearn.metrics import precision_recall_fscore_support
    from sklearn.model_selection import GroupShuffleSplit, train_test_split
except ImportError as exc:  # pragma: no cover
    raise SystemExit(
        "Training dependencies are missing. Install them with:\n"
        "  pip install -r ai/training/teacher_intent/requirements.txt"
    ) from exc


def load_records(paths: list[Path]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in paths:
        if not path.exists():
            raise SystemExit(f"dataset not found: {path}")
        with path.open(encoding="utf-8") as handle:
            for line_number, line in enumerate(handle, start=1):
                line = line.strip()
                if not line:
                    continue
                try:
                    record = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise SystemExit(f"{path}:{line_number}: invalid JSON: {exc}") from exc
                labels = [label for label in record.get("labels", []) if label in LABELS]
                if not labels:
                    labels = ["other"]
                record["labels"] = labels
                records.append(record)
    if not records:
        raise SystemExit("no training records")
    return records


def record_names(record: dict[str, Any]) -> list[str]:
    names = record.get("student_names")
    if isinstance(names, list):
        return [str(name) for name in names if name]
    name = record.get("student_name")
    if name:
        return [str(name)]
    if record.get("source") == "synthetic":
        return NAMES
    return []


def prepare_texts(records: list[dict[str, Any]]) -> list[str]:
    return [prepare(str(record.get("text", "")), record_names(record)) for record in records]


def label_matrix(records: list[dict[str, Any]]) -> np.ndarray:
    index = {label: position for position, label in enumerate(LABELS)}
    matrix = np.zeros((len(records), len(LABELS)), dtype=int)
    for row, record in enumerate(records):
        for label in record["labels"]:
            matrix[row, index[label]] = 1
    return matrix


def split_indices(
    records: list[dict[str, Any]], test_size: float, seed: int
) -> tuple[np.ndarray, np.ndarray]:
    groups = [
        str(record.get("group") or record.get("id") or index)
        for index, record in enumerate(records)
    ]
    if len(set(groups)) >= 4:
        splitter = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=seed)
        train, test = next(splitter.split(records, groups=groups))
        return np.asarray(train), np.asarray(test)
    train, test = train_test_split(
        np.arange(len(records)), test_size=test_size, random_state=seed, shuffle=True
    )
    return np.asarray(train), np.asarray(test)


def fit_label(
    features,
    targets: np.ndarray,
    *,
    seed: int,
) -> tuple[np.ndarray, float]:
    if int(targets.sum()) == 0:
        return np.zeros(features.shape[1], dtype=float), -10.0
    if int(targets.sum()) == len(targets):
        return np.zeros(features.shape[1], dtype=float), 10.0
    model = LogisticRegression(
        C=4.0,
        class_weight="balanced",
        max_iter=3000,
        random_state=seed,
        solver="liblinear",
    )
    model.fit(features, targets)
    return np.asarray(model.coef_[0], dtype=float), float(model.intercept_[0])


def evaluate(
    features,
    matrix: np.ndarray,
    weights: list[np.ndarray],
    intercepts: list[float],
    *,
    threshold: float | None,
) -> dict[str, Any]:
    scores = np.column_stack(
        [_sigmoid(features @ weight + intercept) for weight, intercept in zip(weights, intercepts)]
    )
    if threshold is None:
        predictions = np.column_stack(
            [scores[:, index] >= THRESHOLDS[label] for index, label in enumerate(LABELS)]
        ).astype(int)
    else:
        predictions = (scores >= threshold).astype(int)
    precision, recall, f1, support = precision_recall_fscore_support(
        matrix, predictions, average=None, zero_division=0
    )
    per_label = {}
    for index, label in enumerate(LABELS):
        per_label[label] = {
            "precision": round(float(precision[index]), 4),
            "recall": round(float(recall[index]), 4),
            "f1": round(float(f1[index]), 4),
            "support": int(support[index]),
        }
    macro = {
        "precision": round(float(np.mean(precision)), 4),
        "recall": round(float(np.mean(recall)), 4),
        "f1": round(float(np.mean(f1)), 4),
    }
    return {"threshold": threshold, "per_label": per_label, "macro": macro}


def _sigmoid(values):
    return 1.0 / (1.0 + np.exp(-values))


def export_model(
    vectorizer: TfidfVectorizer,
    weights: list[np.ndarray],
    intercepts: list[float],
    *,
    metrics: dict[str, Any],
    training_size: int,
) -> dict[str, Any]:
    vocabulary = [str(value) for value in vectorizer.get_feature_names_out()]
    return {
        "format": MODEL_FORMAT,
        "version": MODEL_VERSION,
        "labels": list(LABELS),
        "ngram_min": 1,
        "ngram_max": 3,
        "vocabulary": vocabulary,
        "idf": [round(float(value), 8) for value in vectorizer.idf_],
        "weights": [
            [round(float(value), 6) for value in weight] for weight in weights
        ],
        "intercepts": [round(float(value), 6) for value in intercepts],
        "thresholds": THRESHOLDS,
        "metrics": metrics,
        "training_size": training_size,
        "trained_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "notes": (
            "char n-gram TF-IDF + independent logistic regression per label; "
            "train offline, infer in Java or the reference Python infer.py"
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--data",
        action="append",
        type=Path,
        help="JSONL dataset; repeat for multiple files (default: seed corpus)",
    )
    model_dir = Path(__file__).resolve().parents[2] / "models" / "teacher_intent" / "v1"
    parser.add_argument(
        "--out",
        type=Path,
        default=model_dir / "model.json",
    )
    parser.add_argument(
        "--metrics-out",
        type=Path,
        default=model_dir / "metrics.json",
    )
    parser.add_argument("--test-size", type=float, default=0.25)
    parser.add_argument("--seed", type=int, default=13)
    args = parser.parse_args()

    data_paths = args.data
    if not data_paths:
        seed_path = Path(__file__).with_name("data") / "seed.jsonl"
        if not seed_path.exists():
            seed_path.parent.mkdir(parents=True, exist_ok=True)
            with seed_path.open("w", encoding="utf-8") as handle:
                for record in build_records():
                    handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        data_paths = [seed_path]

    records = load_records(data_paths)
    texts = prepare_texts(records)
    matrix = label_matrix(records)
    label_counts = Counter(label for record in records for label in record["labels"])
    print("records:", len(records))
    print("labels:", dict(sorted(label_counts.items())))

    train_index, test_index = split_indices(records, args.test_size, args.seed)
    train_texts = [texts[index] for index in train_index]
    test_texts = [texts[index] for index in test_index]
    train_matrix = matrix[train_index]
    test_matrix = matrix[test_index]
    print(f"train={len(train_texts)} test={len(test_texts)}")

    vectorizer = TfidfVectorizer(
        analyzer="char",
        ngram_range=(1, 3),
        min_df=1,
        max_features=6000,
        lowercase=False,
        norm="l2",
        sublinear_tf=False,
        smooth_idf=True,
    )
    train_features = vectorizer.fit_transform(train_texts)
    test_features = vectorizer.transform(test_texts)
    print("vocabulary:", len(vectorizer.vocabulary_))

    weights: list[np.ndarray] = []
    intercepts: list[float] = []
    for position, label in enumerate(LABELS):
        weight, intercept = fit_label(train_features, train_matrix[:, position], seed=args.seed)
        weights.append(weight)
        intercepts.append(intercept)

    metrics = {
        "at_0_5": evaluate(test_features, test_matrix, weights, intercepts, threshold=0.5),
        "at_label_thresholds": evaluate(
            test_features, test_matrix, weights, intercepts, threshold=None
        ),
        "label_counts": dict(sorted(label_counts.items())),
        "train_size": len(train_texts),
        "test_size": len(test_texts),
        "data_files": [str(path) for path in data_paths],
    }
    print("macro F1 @0.5:", metrics["at_0_5"]["macro"]["f1"])
    print("macro F1 @thresholds:", metrics["at_label_thresholds"]["macro"]["f1"])

    model = export_model(
        vectorizer,
        weights,
        intercepts,
        metrics=metrics,
        training_size=len(train_texts),
    )
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8") as handle:
        json.dump(model, handle, ensure_ascii=False, separators=(",", ":"))
    args.metrics_out.parent.mkdir(parents=True, exist_ok=True)
    with args.metrics_out.open("w", encoding="utf-8") as handle:
        json.dump(metrics, handle, ensure_ascii=False, indent=2)
    print("wrote", args.out, f"{args.out.stat().st_size / 1024:.1f} KiB")
    print("wrote", args.metrics_out)


if __name__ == "__main__":
    main()
