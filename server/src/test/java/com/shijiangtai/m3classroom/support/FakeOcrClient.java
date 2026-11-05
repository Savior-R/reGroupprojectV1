package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.OcrClient;
import com.shijiangtai.m3classroom.application.port.OcrResult;
import com.shijiangtai.m3classroom.domain.session.OcrBox;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controllable OCR port for unit and integration tests. The default response is a successful
 * recognition with confidence {@code 0.90}; {@link #failWith(RuntimeException)} makes the next
 * calls throw, and {@link #block()} holds recognition until {@link #release()} so a test can
 * observe the synchronous {@code pending} state before the async task finishes.
 */
public class FakeOcrClient implements OcrClient {

    public static final OcrResult DEFAULT_RESULT = new OcrResult(
            "识别文本", List.of(new OcrBox("识别文本", 0.0, 0.0, 1.0, 1.0, new BigDecimal("0.90"))),
            new BigDecimal("0.90"), "fake-ocr");

    private final AtomicReference<OcrResult> result = new AtomicReference<>(DEFAULT_RESULT);
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile CountDownLatch gate;

    public void reset() {
        result.set(DEFAULT_RESULT);
        failure.set(null);
        calls.set(0);
        release();
    }

    public void succeedWith(OcrResult nextResult) {
        failure.set(null);
        result.set(nextResult);
    }

    public void failWith(RuntimeException failure) {
        this.failure.set(failure);
    }

    /** Blocks {@link #recognize(byte[])} until {@link #release()} is called. */
    public void block() {
        gate = new CountDownLatch(1);
    }

    public void release() {
        CountDownLatch current = gate;
        gate = null;
        if (current != null) {
            current.countDown();
        }
    }

    public int calls() {
        return calls.get();
    }

    @Override
    public OcrResult recognize(byte[] jpeg) {
        calls.incrementAndGet();
        awaitGate();
        RuntimeException error = failure.get();
        if (error != null) {
            throw error;
        }
        return result.get();
    }

    private void awaitGate() {
        CountDownLatch current = gate;
        if (current == null) {
            return;
        }
        try {
            if (!current.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FakeOcrClient gate timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FakeOcrClient interrupted", exception);
        }
    }
}
