package com.shijiangtai.m4eval.application.view;

import com.shijiangtai.m4eval.application.port.EvaluationInputQuery;
import java.util.Map;
import java.util.UUID;

public record InputEventView(
        UUID id,
        long seq,
        String type,
        long timestampMs,
        String actor,
        String target,
        Map<String, Object> payload) {

    public static InputEventView from(EvaluationInputQuery.EventRow row) {
        return new InputEventView(
                row.id(),
                row.seq(),
                row.type(),
                row.timestampMs(),
                row.actor(),
                row.target(),
                row.payload());
    }
}
