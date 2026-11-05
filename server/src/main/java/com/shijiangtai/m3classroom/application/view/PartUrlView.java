package com.shijiangtai.m3classroom.application.view;

/** Presigned part PUT URL; serialized snake_case by the global Jackson strategy. */
public record PartUrlView(String url, int partNumber, long expiresInSeconds) {
}
