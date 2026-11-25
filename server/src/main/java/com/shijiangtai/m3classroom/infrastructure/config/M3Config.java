package com.shijiangtai.m3classroom.infrastructure.config;

import com.shijiangtai.m3classroom.application.port.AfterCommitPort;
import com.shijiangtai.m3classroom.application.port.AsrStreamClient;
import com.shijiangtai.m3classroom.application.port.ContentClient;
import com.shijiangtai.m3classroom.application.port.ContentRequest;
import com.shijiangtai.m3classroom.application.port.ContentResponse;
import com.shijiangtai.m3classroom.application.port.MaterialTextExtractor;
import com.shijiangtai.m3classroom.application.port.OcrClient;
import com.shijiangtai.m3classroom.application.port.SchedulerPort;
import com.shijiangtai.m3classroom.application.port.TeacherSpeechClassifier;
import com.shijiangtai.m3classroom.domain.classroom.HybridTeacherIntentClassifier;
import com.shijiangtai.m3classroom.domain.classroom.RuleBasedTeacherIntentClassifier;
import com.shijiangtai.m3classroom.domain.classroom.TeacherIntent;
import com.shijiangtai.m3classroom.domain.classroom.TeacherIntentClassifier;
import com.shijiangtai.m3classroom.domain.task.TaskPublishPolicy;
import com.shijiangtai.m3classroom.domain.task.TaskSnapshotFactory;
import com.shijiangtai.m3classroom.infrastructure.events.SpringAfterCommitHook;
import com.shijiangtai.m3classroom.infrastructure.events.SpringEventPublisher;
import com.shijiangtai.m3classroom.infrastructure.material.CompositeMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.material.DocxMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.material.PdfMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.material.PlainTextMaterialTextExtractor;
import com.shijiangtai.m3classroom.infrastructure.perception.LinearTeacherIntentClassifier;
import com.shijiangtai.m3classroom.infrastructure.perception.ShadowTeacherIntentClassifier;
import com.shijiangtai.m3classroom.infrastructure.time.SystemClockProvider;
import com.shijiangtai.m3classroom.infrastructure.time.SystemScheduler;
import com.shijiangtai.m3classroom.infrastructure.time.UuidIdGenerator;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.EventPublisher;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class M3Config {

    private static final Logger LOG = LoggerFactory.getLogger(M3Config.class);

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
     * Teacher-intent classifier. Rules are the default.
     *
     * <p>{@code m3.classroom.teacher-intent.mode}:
     * <ul>
     *   <li>{@code off} (default): rules only, even when a model path is configured;</li>
     *   <li>{@code shadow}: rules drive the classroom, model predictions are logged for
     *       comparison;</li>
     *   <li>{@code active}: model + rules hybrid. {@code active-labels} limits which labels the
     *       model may contribute; the default excludes the high-risk call-on / ending labels.</li>
     * </ul>
     * A missing / broken model falls back to rules without failing startup.
     */
    @Bean
    @ConditionalOnMissingBean(TeacherIntentClassifier.class)
    public TeacherIntentClassifier teacherIntentClassifier(
            @Value("${m3.classroom.teacher-intent.model-path:}") String modelPath,
            @Value("${m3.classroom.teacher-intent.mode:off}") String mode,
            @Value("${m3.classroom.teacher-intent.active-labels:"
                    + "ask_class,ask_student,prompt_student_question,comment_answer}")
            String activeLabels,
            ObjectMapper objectMapper) {
        TeacherIntentClassifier rules = new RuleBasedTeacherIntentClassifier();
        if (modelPath == null || modelPath.isBlank()) {
            return rules;
        }
        String normalizedMode = mode == null ? "off" : mode.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(normalizedMode)) {
            return rules;
        }
        try {
            TeacherIntentClassifier model =
                    new LinearTeacherIntentClassifier(Path.of(modelPath), objectMapper);
            if ("shadow".equals(normalizedMode)) {
                LOG.info("Loaded local teacher-intent model from {} in shadow mode", modelPath);
                return new ShadowTeacherIntentClassifier(model, rules);
            }
            if ("active".equals(normalizedMode)) {
                Set<TeacherIntent> modelLabels = parseActiveLabels(activeLabels);
                LOG.info("Loaded local teacher-intent model from {} in active mode; "
                                + "model labels={}",
                        modelPath,
                        modelLabels.isEmpty() ? "all" : modelLabels);
                return new HybridTeacherIntentClassifier(model, rules, modelLabels);
            }
            LOG.warn("Unknown teacher-intent mode '{}'; using rules", mode);
            return rules;
        } catch (RuntimeException exception) {
            LOG.warn("Failed to load local teacher-intent model {}; using rules",
                    modelPath, exception);
            return rules;
        }
    }

    private static Set<TeacherIntent> parseActiveLabels(String value) {
        if (value == null || value.isBlank() || "*".equals(value.trim())) {
            return Set.of();
        }
        Set<TeacherIntent> labels = EnumSet.noneOf(TeacherIntent.class);
        for (String part : value.split(",")) {
            String candidate = part.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                labels.add(TeacherIntent.fromWire(candidate));
            } catch (IllegalArgumentException exception) {
                LOG.warn("Unknown teacher-intent label '{}' in active-labels; ignored", candidate);
            }
        }
        return labels;
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

    /**
     * Fallback streaming ASR until {@code m3.ai.base-url} enables the Python adapter. The
     * perception socket catches the failure and closes without touching the session.
     */
    @Bean
    @ConditionalOnMissingBean(AsrStreamClient.class)
    public AsrStreamClient asrStreamClient() {
        return request -> {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "ASR 服务不可用");
        };
    }
}
