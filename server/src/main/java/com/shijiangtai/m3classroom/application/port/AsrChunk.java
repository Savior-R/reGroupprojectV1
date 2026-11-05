package com.shijiangtai.m3classroom.application.port;

import java.math.BigDecimal;

/** One recognized speech segment from the streaming ASR service. */
public record AsrChunk(
        String text, boolean finalChunk, Long beginMs, Long endMs, BigDecimal confidence) {
}
