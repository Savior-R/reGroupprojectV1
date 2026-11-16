package com.shijiangtai.m4eval.application.view;

/** Callback acknowledgement; {@code ignored} marks a replayed / stale attempt (06 §5.7). */
public record CallbackResultView(String evaluationId, String status, boolean ignored) {
}
