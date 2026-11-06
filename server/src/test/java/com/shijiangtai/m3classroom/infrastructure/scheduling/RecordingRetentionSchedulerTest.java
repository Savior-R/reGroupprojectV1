package com.shijiangtai.m3classroom.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shijiangtai.m3classroom.application.RecordingRetentionService;
import com.shijiangtai.m3classroom.application.RecordingRetentionService.RetentionRun;
import com.shijiangtai.shared.application.ClockProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RecordingRetentionSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-11-06T01:02:03Z");

    @Test
    void propertiesDefaultToEnabledWithOneMinuteDelayAndOneHourInterval() {
        M3RetentionProperties properties = new M3RetentionProperties(null, null, null);

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.initialDelay()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.interval()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void disabledSchedulerDoesNotRunCleanupAndCloseIsIdempotent() throws InterruptedException {
        RecordingRetentionService service = mock(RecordingRetentionService.class);
        ClockProvider clock = mock(ClockProvider.class);
        CountDownLatch cleanupCalls = new CountDownLatch(1);
        when(service.cleanup(any())).thenAnswer(invocation -> {
            cleanupCalls.countDown();
            return new RetentionRun(0, 0, 0);
        });
        RecordingRetentionScheduler scheduler = new RecordingRetentionScheduler(
                service, clock,
                new M3RetentionProperties(false, Duration.ofMillis(10), Duration.ofMillis(10)));

        scheduler.start();

        assertThat(cleanupCalls.await(200, TimeUnit.MILLISECONDS)).isFalse();
        verifyNoInteractions(service, clock);
        assertThatCode(scheduler::close).doesNotThrowAnyException();
        assertThatCode(scheduler::close).doesNotThrowAnyException();
    }

    @Test
    void runSafelyCallsCleanupWithClockNow() {
        RecordingRetentionService service = mock(RecordingRetentionService.class);
        when(service.cleanup(NOW)).thenReturn(new RetentionRun(1, 2, 3));
        RecordingRetentionScheduler scheduler = new RecordingRetentionScheduler(
                service, () -> NOW,
                new M3RetentionProperties(true, Duration.ofMinutes(1), Duration.ofHours(1)));

        scheduler.runSafely();

        verify(service).cleanup(NOW);
    }

    @Test
    void runSafelySwallowsRuntimeException() {
        RecordingRetentionService service = mock(RecordingRetentionService.class);
        when(service.cleanup(NOW))
                .thenThrow(new IllegalStateException("candidate query failed"));
        RecordingRetentionScheduler scheduler = new RecordingRetentionScheduler(
                service, () -> NOW,
                new M3RetentionProperties(true, Duration.ofMinutes(1), Duration.ofHours(1)));

        assertThatCode(scheduler::runSafely).doesNotThrowAnyException();

        verify(service).cleanup(NOW);
    }

    @Test
    void enabledSchedulerRunsCleanupAfterInitialDelay() throws InterruptedException {
        RecordingRetentionService service = mock(RecordingRetentionService.class);
        CountDownLatch cleanupCalls = new CountDownLatch(1);
        when(service.cleanup(NOW)).thenAnswer(invocation -> {
            cleanupCalls.countDown();
            return new RetentionRun(0, 0, 0);
        });
        RecordingRetentionScheduler scheduler = new RecordingRetentionScheduler(
                service, () -> NOW,
                new M3RetentionProperties(true, Duration.ofMillis(10), Duration.ofHours(1)));

        try {
            scheduler.start();
            assertThat(cleanupCalls.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.close();
        }

        verify(service, atLeastOnce()).cleanup(NOW);
    }
}