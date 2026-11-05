package com.shijiangtai.m3classroom.application.port;

/**
 * Port to the Python OCR service (05A §6.2). Implementations may block; the caller enforces the
 * hard timeout and degrades the frame on failure.
 */
public interface OcrClient {

    OcrResult recognize(byte[] jpeg);
}
