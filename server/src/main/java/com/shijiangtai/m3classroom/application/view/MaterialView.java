package com.shijiangtai.m3classroom.application.view;

import java.util.UUID;

public record MaterialView(
        UUID materialId,
        String filename,
        long size,
        String mime,
        int charCount,
        String parseStatus,
        String preview,
        String parsedText) {
}
