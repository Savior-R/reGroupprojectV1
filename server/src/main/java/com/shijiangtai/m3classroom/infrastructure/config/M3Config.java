package com.shijiangtai.m3classroom.infrastructure.config;

import com.shijiangtai.m3classroom.application.port.AfterCommitPort;
import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import com.shijiangtai.m3classroom.application.port.OcrClient;
import com.shijiangtai.m3classroom.application.port.RecordingLifecyclePort;
import com.shijiangtai.m3classroom.application.port.SchedulerPort;
import com.shijiangtai.m3classroom.application.port.TeacherSpeechClassifier;
import com.shijiangtai.m3classroom.domain.task.TaskPublishPolicy;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.infrastructure.events.SpringAfterCommitHook;
import com.shijiangtai.m3classroom.infrastructure.events.SpringEventPublisher;
import com.shijiangtai.m3classroom.infrastructure.material.CompositeMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.material.DocxMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.material.PdfMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.material.PlainTextMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.time.SystemClockProvider;
import com.shijiangtai.m3classroom.infrastructure.time.SystemScheduler;
import com.shijiangtai.m3classroom.infrastructure.time.UuidIdGenerator;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.EventPublisher;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SessionId;
import com.shijiangtai.shared.domain.TaskId;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class M3Config {

    @Bean
    public TaskSnapshotFactory taskSnapshotFactory() {
        return new TaskSnapshotFactory();
    }

    @Bean
    public TaskPublishPolicy taskPublishPolicy() {
        return new TaskPublishPolicy();
    }

    @Bean
    public IdGenerator idGenerator() {
        return new UuidIdGenerator();
    }

    @Bean
    public ClockProvider clockProvider() {
        return new SystemClockProvider();
    }

    @Bean
    public EventPublisher eventPublisher(ApplicationEventPublisher publisher) {
        return new SpringEventPublisher(publisher);
    }

    @Bean
    public AfterCommitPort afterCommitPort() {
        return new SpringAfterCommitHook();
    }

    @Bean
    public MaterialTextExtractor materialTextExtractor() {
        return new CompositeMaterialTextExtractor(List.of(
                new PdfMaterialTextExtractor(),
                new DocxMaterialTextExtractor(),
                new PlainTextMaterialTextExtractor()));
    }

    /** Single-threaded production scheduler for hand windows, ticks and grace timers. */
    @Bean(destroyMethod = "close")
    public SchedulerPort schedulerPort() {
        return new SystemScheduler();
    }

    /**
     * Fallback content client until the HTTP adapter to the Python content service exists.
     * {@code ContentGenerationService} converts the failure into the fixed template fallback.
     */
    @Bean
    @ConditionalOnMissingBean(ContentClient.class)
    public ContentClient contentClient() {
        return new ContentClient() {
            @Override
            public ContentResponse question(ContentRequest request) {
                throw unavailable();
            }

            @Override
            public ContentResponse answer(ContentRequest request) {
                throw unavailable();
            }

            private DomainException unavailable() {
                return new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "内容服务不可用");
            }
        };
    }

    /**
     * Fallback OCR until {@code m3.ai.base-url} enables the Python adapter. The screen pipeline
     * catches the failure and marks the frame {@code failed}; the session is unaffected.
     */
    @Bean
    @ConditionalOnMissingBean(OcrClient.class)
    public OcrClient ocrClient() {
        return jpeg -> {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "OCR 服务不可用");
        };
    }

    /**
     * Fallback classifier until {@code m3.ai.base-url} enables the Python adapter. The perception
     * pipeline logs the failure and drops the classification.
     */
    @Bean
    @ConditionalOnMissingBean(TeacherSpeechClassifier.class)
    public TeacherSpeechClassifier teacherSpeechClassifier() {
        return (text, recentTexts, mode) -> {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "教师话语分类服务不可用");
        };
    }

    /** Plan 1 has no recording adapter yet; Plan 2 replaces this no-op with the real one. */
    @Bean
    @ConditionalOnMissingBean(RecordingLifecyclePort.class)
    public RecordingLifecyclePort recordingLifecyclePort() {
        return new RecordingLifecyclePort() {
            @Override
            public void abortAll(SessionId sessionId) {
                // no-op until the recording module is wired in Plan 2
            }

            @Override
            public void deleteOtherAttempts(AccountId student, TaskId task, SessionId keep) {
                // no-op until the recording module is wired in Plan 2
            }
        };
    }
}
