package com.shijiangtai.m3classroom.application.port;

/** Port to the Python streaming ASR service. */
public interface AsrStreamClient {

    /**
     * Opens a streaming ASR session. The call blocks for the WebSocket handshake and throws
     * {@link RuntimeException} when the service is unavailable.
     */
    AsrSession open(AsrRequest request);
}
