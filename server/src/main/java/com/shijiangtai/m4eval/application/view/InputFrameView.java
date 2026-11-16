package com.shijiangtai.m4eval.application.view;

import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import java.util.UUID;

public record InputFrameView(
        UUID id,
        long timestampMs,
        String text,
        Double confidence,
        String frameType,
        String objectKey,
        String contentHash,
        String status) {

    public static InputFrameView from(EvaluationInputQuery.FrameRow row) {
        return new InputFrameView(
                row.id(),
                row.timestampMs(),
                row.text(),
                row.confidence(),
                row.frameType(),
                row.objectKey(),
                row.contentHash(),
                row.status());
    }
}
