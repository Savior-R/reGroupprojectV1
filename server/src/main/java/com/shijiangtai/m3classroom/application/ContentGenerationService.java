package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Calls the AI content service with hard timeouts and template fallback (05A §7.4, P0).
 *
 * <p>Question generation has a 2 s budget and answer generation 3 s. Any client exception,
 * timeout, rejection or blank response yields the fixed template with confidence {@code 0.0},
 * so a slow or failing Python service never blocks the classroom longer than the budget.
 *
 * <p>Blocking calls run on a dedicated bounded pool of daemon threads (never
 * {@link java.util.concurrent.ForkJoinPool#commonPool()}); a timed-out call is cancelled with
 * interrupt so a stuck supplier cannot hold a worker forever.
 */
public final class ContentGenerationService implements AutoCloseable {

    public static final Duration QUESTION_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(3);
    public static final String QUESTION_FALLBACK = "老师，这里为什么要这样理解？";
    public static final String ANSWER_FALLBACK = "老师，我认为这一段的重点是……";
    public static final String FALLBACK_MODEL_VERSION = "template";
    public static final String FALLBACK_PROMPT_VERSION = "template";

    private static final int WORKER_THREADS = 2;
    private static final int QUEUE_CAPACITY = 64;

    private final ContentClient client;
    private final Duration questionTimeout;
    private final Duration answerTimeout;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    public ContentGenerationService(ContentClient client) {
        this(client, QUESTION_TIMEOUT, ANSWER_TIMEOUT);
    }

    public ContentGenerationService(
            ContentClient client, Duration questionTimeout, Duration answerTimeout) {
        this(client, questionTimeout, answerTimeout, newExecutor(), true);
    }

    /** Test seam: the injected executor is owned by the caller and is not shut down here. */
    public ContentGenerationService(
            ContentClient client,
            Duration questionTimeout,
            Duration answerTimeout,
            ExecutorService executor) {
        this(client, questionTimeout, answerTimeout, executor, false);
    }

    private ContentGenerationService(
            ContentClient client,
            Duration questionTimeout,
            Duration answerTimeout,
            ExecutorService executor,
            boolean ownsExecutor) {
        this.client = Objects.requireNonNull(client, "client");
        this.questionTimeout = Objects.requireNonNull(questionTimeout, "questionTimeout");
        this.answerTimeout = Objects.requireNonNull(answerTimeout, "answerTimeout");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
    }

    public ContentResponse questionOrFallback(ContentRequest request) {
        Objects.requireNonNull(request, "request");
        return call(() -> client.question(request), questionTimeout, QUESTION_FALLBACK);
    }

    public ContentResponse answerOrFallback(ContentRequest request) {
        Objects.requireNonNull(request, "request");
        return call(() -> client.answer(request), answerTimeout, ANSWER_FALLBACK);
    }

    @Override
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private ContentResponse call(
            Supplier<ContentResponse> call, Duration timeout, String fallbackText) {
        Future<ContentResponse> future = null;
        try {
            future = executor.submit(call::get);
            ContentResponse response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response == null || response.text() == null || response.text().isBlank()) {
                return fallback(fallbackText);
            }
            return response;
        } catch (TimeoutException exception) {
            cancel(future);
            return fallback(fallbackText);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel(future);
            return fallback(fallbackText);
        } catch (ExecutionException | RuntimeException exception) {
            cancel(future);
            return fallback(fallbackText);
        }
    }

    private static void cancel(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private static ContentResponse fallback(String text) {
        return new ContentResponse(text, 0.0, FALLBACK_MODEL_VERSION, FALLBACK_PROMPT_VERSION);
    }

    private static ExecutorService newExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "content-generation");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
                WORKER_THREADS,
                WORKER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
