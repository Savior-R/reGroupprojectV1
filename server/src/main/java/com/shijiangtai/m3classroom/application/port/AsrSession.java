package com.shijiangtai.m3classroom.application.port;

import java.util.function.Consumer;

/**
 * One streaming ASR session.
 *
 * <p>{@link #send(byte[])} never blocks: audio is queued for the sender thread. A full queue
 * surfaces through the error listener and closes the session. {@link #finish()} tells the service
 * that no more audio follows; {@link #close()} may be called before or after the handshake.
 */
public interface AsrSession extends AutoCloseable {

    /** Queues one PCM frame for delivery. A zero-length frame is equivalent to {@link #finish()}. */
    void send(byte[] pcm);

    /** Signals end of audio. Idempotent. */
    void finish();

    void onChunk(Consumer<AsrChunk> listener);

    void onClosed(Runnable listener);

    void onError(Consumer<RuntimeException> listener);

    @Override
    void close();
}
