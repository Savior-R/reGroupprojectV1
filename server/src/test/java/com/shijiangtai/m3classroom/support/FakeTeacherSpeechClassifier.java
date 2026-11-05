package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.ClassifyResult;
import com.shijiangtai.m3classroom.application.port.TeacherSpeechClassifier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Configurable teacher-speech classifier for perception WebSocket tests. */
public final class FakeTeacherSpeechClassifier implements TeacherSpeechClassifier {

    private static final ClassifyResult DEFAULT_RESULT = new ClassifyResult(
            "statement", new BigDecimal("0.90"), "fake-classifier", "prompt-v1");

    private final AtomicReference<ClassifyResult> result =
            new AtomicReference<>(DEFAULT_RESULT);
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> classifyGate = new AtomicReference<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public void reset() {
        result.set(DEFAULT_RESULT);
        failure.set(null);
        classifyGate.set(null);
        calls.clear();
    }

    public void respondWith(String label, String confidence) {
        result.set(new ClassifyResult(
                Objects.requireNonNull(label, "label"),
                new BigDecimal(Objects.requireNonNull(confidence, "confidence")),
                "fake-classifier",
                "prompt-v1"));
        failure.set(null);
    }

    public void failWith(RuntimeException nextFailure) {
        failure.set(Objects.requireNonNull(nextFailure, "nextFailure"));
    }

    /**
     * Makes the next {@code classify} call block until {@link #releaseClassify()}. Used to keep a
     * queued classification in flight while a normal {@code audio.stop} closes the socket.
     */
    public void blockOnClassify(CountDownLatch release) {
        classifyGate.set(Objects.requireNonNull(release, "release"));
    }

    public void releaseClassify() {
        CountDownLatch gate = classifyGate.getAndSet(null);
        if (gate != null) {
            gate.countDown();
        }
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    @Override
    public ClassifyResult classify(String text, List<String> recentTexts, String mode) {
        List<String> texts = recentTexts == null ? List.of() : List.copyOf(recentTexts);
        calls.add(new Call(text, texts, mode));
        CountDownLatch gate = classifyGate.get();
        if (gate != null) {
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        RuntimeException error = failure.get();
        if (error != null) {
            throw error;
        }
        return result.get();
    }

    /** One recorded classification request. */
    public record Call(String text, List<String> recentTexts, String mode) {
    }
}
