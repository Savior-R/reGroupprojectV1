package com.shijiangtai.m4eval.application.view;

import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import java.util.UUID;

public record InputTextView(
        UUID id,
        String speakerType,
        UUID speakerRef,
        String source,
        String kind,
        String text,
        Long startMs,
        Long endMs,
        long atMs,
        Double confidence,
        String modelVersion,
        String promptVersion) {

    public static InputTextView from(EvaluationInputQuery.TextRow row) {
        return new InputTextView(
                row.id(),
                row.speakerType(),
                row.speakerRef(),
                row.source(),
                row.kind(),
                row.text(),
                row.startMs(),
                row.endMs(),
                row.atMs(),
                row.confidence(),
                row.modelVersion(),
                row.promptVersion());
    }
}
