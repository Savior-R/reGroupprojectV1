"""Reference pure-Python inference for the exported linear model.

This module is intentionally dependency-free. It is used by tests to verify
that the exported JSON matches the sklearn training transform, and it documents
the exact math the Java ``LinearTeacherIntentClassifier`` implements.
"""

from __future__ import annotations

import json
import math
from collections import Counter
from pathlib import Path
from typing import Any, Iterable

from .labels import HIT_FLOOR, PRECEDENCE
from .text import prepare


class LinearTeacherIntentModel:
    def __init__(self, payload: dict[str, Any]) -> None:
        self.format = payload["format"]
        self.version = payload["version"]
        self.labels: list[str] = list(payload["labels"])
        self.ngram_min = int(payload.get("ngram_min", 1))
        self.ngram_max = int(payload.get("ngram_max", 3))
        self.idf: list[float] = [float(value) for value in payload["idf"]]
        self.weights: list[list[float]] = [
            [float(value) for value in row] for row in payload["weights"]
        ]
        self.intercepts: list[float] = [float(value) for value in payload["intercepts"]]
        self.vocabulary: dict[str, int] = {
            str(ngram): index for index, ngram in enumerate(payload["vocabulary"])
        }
        self.thresholds: dict[str, float] = {
            str(label): float(value) for label, value in payload.get("thresholds", {}).items()
        }

    @classmethod
    def load(cls, path: str | Path) -> "LinearTeacherIntentModel":
        with Path(path).open(encoding="utf-8") as handle:
            return cls(json.load(handle))

    def scores(self, text: str, names: Iterable[str] = ()) -> dict[str, float]:
        prepared = prepare(text, names)
        counts = Counter(
            prepared[index : index + size]
            for size in range(self.ngram_min, self.ngram_max + 1)
            for index in range(max(0, len(prepared) - size + 1))
        )
        sparse: list[tuple[int, float]] = []
        squared_norm = 0.0
        for ngram, count in counts.items():
            index = self.vocabulary.get(ngram)
            if index is None:
                continue
            value = count * self.idf[index]
            sparse.append((index, value))
            squared_norm += value * value
        norm = math.sqrt(squared_norm)
        result: dict[str, float] = {}
        for position, label in enumerate(self.labels):
            score = self.intercepts[position]
            weight = self.weights[position]
            for index, value in sparse:
                score += time_value(value, norm) * weight[index]
            result[label] = sigmoid(score)
        return result

    def predict(
        self,
        text: str,
        names: Iterable[str] = (),
        *,
        floor: float = HIT_FLOOR,
    ) -> dict[str, Any]:
        scores = self.scores(text, names)
        hits = [
            {"label": label, "confidence": round(score, 6)}
            for label, score in scores.items()
            if score >= floor
        ]
        if not hits:
            primary = "other"
            confidence = scores.get("other", 0.5)
            hits = [{"label": "other", "confidence": round(confidence, 6)}]
        else:
            # A trained model's primary is its highest-probability label. The
            # precedence list is only a deterministic tie-break for equal scores.
            best = max(
                hits,
                key=lambda hit: (hit["confidence"], -PRECEDENCE.index(hit["label"])),
            )
            primary = best["label"]
            confidence = best["confidence"]
            hits.sort(key=lambda hit: hit["confidence"], reverse=True)
        return {
            "primary_intent": primary,
            "confidence": confidence,
            "intents": hits,
            "model_version": self.version,
        }


def sigmoid(value: float) -> float:
    if value >= 0:
        exponent = math.exp(-value)
        return 1.0 / (1.0 + exponent)
    exponent = math.exp(value)
    return exponent / (1.0 + exponent)


def time_value(value: float, norm: float) -> float:
    return 0.0 if norm == 0.0 else value / norm
