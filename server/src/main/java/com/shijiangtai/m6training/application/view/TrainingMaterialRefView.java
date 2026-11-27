package com.shijiangtai.m6training.application.view;

/** Owner-facing reference material metadata; the parsed text stays server-side (08 §4.6). */
public record TrainingMaterialRefView(
        String filename,
        String mime,
        long size,
        int charCount) {
}
