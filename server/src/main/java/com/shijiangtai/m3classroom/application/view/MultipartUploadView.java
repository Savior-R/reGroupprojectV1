package com.shijiangtai.m3classroom.application.view;

/** Multipart upload coordinates returned by {@code POST .../multipart/start}. */
public record MultipartUploadView(String uploadId, String objectKey, long partSize) {
}
