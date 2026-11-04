package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Calls the AI content service with hard timeouts and template fallback (05A §7.4, P0).
 *
 * <p>Question generation has a 2 s budget and answer generation 3 s. Any client exception,
 * timeout, null response or blank text yields the fixed template with confidence {@code 0.0},
 * so a slow or failing Python service never blocks the classroom longer than the budget.
 */
public final class ContentGenerationService {

    public static final Duration QUESTION_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(3);
    public static final String QUESTION_FALLBACK = "老师，这里为什么要这样理解？";
    public static final String ANSWER_FALLBACK = "老师，我认为这一段的重点是……";
    public static final String FALLBACK_MODEL_VERSION = "template";
    public static final String FALLBACK_PROMPT_VERSION = "template";

    private final ContentClient client;

    public ContentGenerationService(ContentClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public ContentResponse questionOrFallback(ContentRequest request) {
        Objects.requireNonNull(request, "request");
        return call(() -> client.question(request), QUESTION_TIMEOUT, QUESTION_FALLBACK);
    }

    public ContentResponse answerOrFallback(ContentRequest request) {
        Objects.requireNonNull(request, "request");
        return call(() -> client.answer(request), ANSWER_TIMEOUT, ANSWER_FALLBACK);
    }

    private ContentResponse call(
            Supplier<ContentResponse> call, Duration timeout, String fallbackText) {
        try {
            ContentResponse response = CompletableFuture
                    .supplyAsync(call)
                    .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            if (response == null || response.text() == null || response.text().isBlank()) {
                return fallback(fallbackText);
            }
            return response;
        } catch (RuntimeException exception) {
            return fallback(fallbackText);
        }
    }

    private static ContentResponse fallback(String text) {
        return new ContentResponse(text, 0.0, FALLBACK_MODEL_VERSION, FALLBACK_PROMPT_VERSION);
    }
}
