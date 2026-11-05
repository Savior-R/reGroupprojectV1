package com.shijiangtai.m3classroom.application.view;

/**
 * Recording stream metadata exposed to the browser (05B §4/§5). Bytes are never served here;
 * the client uploads through presigned part URLs and replays through Task 5's replay endpoint.
 */
public record RecordingStreamView(
        String streamType,
        String status,
        String uploadId,
        String objectKey,
        int partsUploaded,
        long bytesUploaded,
        Long durationMs,
        long offsetMs,
        String mime,
        long partSize) {
}
