package com.shijiangtai.m3classroom.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ContentGenerationServiceTest {

    private static final ContentRequest REQUEST = new ContentRequest(
            "task-1", "session-1", "free", "荷塘月色", List.of(), null,
            "林小雨", "性格外向", 0.7, null);

    @Test
    void defaultTimeoutsMatchContract() {
        assertThat(ContentGenerationService.QUESTION_TIMEOUT).isEqualTo(Duration.ofSeconds(2));
        assertThat(ContentGenerationService.ANSWER_TIMEOUT).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void blockingQuestionTimesOutReturnsFallbackAndCancelsCall() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ContentClient blocking = new ContentClient() {
            @Override
            public ContentResponse question(ContentRequest request) {
                started.countDown();
                try {
                    Thread.sleep(30_000L);
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
                return new ContentResponse("late", 1.0, "model", "prompt");
            }

            @Override
            public ContentResponse answer(ContentRequest request) {
                return new ContentResponse("answer", 0.9, "model", "prompt");
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "content-timeout-test");
            thread.setDaemon(true);
            return thread;
        });
        try (ContentGenerationService service = new ContentGenerationService(
                blocking, Duration.ofMillis(200), Duration.ofSeconds(3), executor)) {
            ContentResponse response = service.questionOrFallback(REQUEST);

            assertThat(started.getCount()).isZero();
            assertThat(response.text()).isEqualTo(ContentGenerationService.QUESTION_FALLBACK);
            assertThat(response.confidence()).isZero();
            assertThat(response.modelVersion())
                    .isEqualTo(ContentGenerationService.FALLBACK_MODEL_VERSION);
            assertThat(interrupted.await(2, TimeUnit.SECONDS))
                    .as("timed-out blocking call must be cancelled and interrupted")
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void clientExceptionReturnsAnswerFallback() {
        ContentClient failing = new ContentClient() {
            @Override
            public ContentResponse question(ContentRequest request) {
                throw new IllegalStateException("content service unavailable");
            }

            @Override
            public ContentResponse answer(ContentRequest request) {
                throw new IllegalStateException("content service unavailable");
            }
        };
        try (ContentGenerationService service = new ContentGenerationService(failing)) {
            ContentResponse response = service.answerOrFallback(REQUEST);

            assertThat(response.text()).isEqualTo(ContentGenerationService.ANSWER_FALLBACK);
            assertThat(response.confidence()).isZero();
            assertThat(response.promptVersion())
                    .isEqualTo(ContentGenerationService.FALLBACK_PROMPT_VERSION);
        }
    }
}
