package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.command.SaveTeacherScoreCommand;
import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.application.view.FinalScoreRow;
import com.shijiangtai.m7teacher.application.view.TeacherScoreRow;
import com.shijiangtai.m7teacher.application.view.TeacherScoreSaveView;
import com.shijiangtai.m7teacher.domain.FinalScore;
import com.shijiangtai.m7teacher.domain.GradingConfig;
import com.shijiangtai.m7teacher.domain.GradingConfigRepository;
import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import com.shijiangtai.m7teacher.domain.ScoreMath;
import com.shijiangtai.m7teacher.domain.ScoringMode;
import com.shijiangtai.m7teacher.domain.TeacherScore;
import com.shijiangtai.m7teacher.domain.TeacherScoreRepository;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single teacher-score save with dimension computation and optimistic versioning
 * (FR-M7-03 / 07 §5).
 */
@Service
public class TeacherScoreService {

    private static final int MAX_COMMENT_LENGTH = 1000;

    private final M7AccessPolicy accessPolicy;
    private final GradingConfigService configService;
    private final GradingConfigRepository configRepository;
    private final TeacherScoreRepository teacherScoreRepository;
    private final FinalScoreService finalScoreService;
    private final UpstreamQueryPort upstream;
    private final ClockProvider clockProvider;

    public TeacherScoreService(
            M7AccessPolicy accessPolicy,
            GradingConfigService configService,
            GradingConfigRepository configRepository,
            TeacherScoreRepository teacherScoreRepository,
            FinalScoreService finalScoreService,
            UpstreamQueryPort upstream,
            ClockProvider clockProvider) {
        this.accessPolicy = accessPolicy;
        this.configService = configService;
        this.configRepository = configRepository;
        this.teacherScoreRepository = teacherScoreRepository;
        this.finalScoreService = finalScoreService;
        this.upstream = upstream;
        this.clockProvider = clockProvider;
    }

    @Transactional
    public TeacherScoreSaveView save(
            CurrentUser teacher, UUID submissionId, SaveTeacherScoreCommand command) {
        Objects.requireNonNull(command, "command");
        SubmissionAccess access = accessPolicy.requireTeacherSubmission(teacher, submissionId);
        TaskContext context = access.taskContext();
        accessPolicy.requireNotArchived(context);
        accessPolicy.requirePublished(context);
        UpstreamQueryPort.Submission submission = access.submission();
        if (!"submitted".equals(submission.status())) {
            throw new M7Exception(M7ErrorCode.SUBMISSION_NOT_SUBMITTED, "提交件不存在或未提交",
                    Map.of("submission_id", submissionId));
        }

        upstream.lockTask(context.task().id());
        GradingConfig config = configService.getOrCreateForUpdate(context, teacher);
        if (config.scoringMode() != command.scoringMode()) {
            throw invalid("scoring_mode", "打分模式与任务配置不一致");
        }
        String comment = validateComment(command.comment());
        Map<String, BigDecimal> dimensions = new LinkedHashMap<>();
        BigDecimal teacherTotal;
        if (config.scoringMode() == ScoringMode.DIMENSION) {
            dimensions = validateDimensions(context, command.dimensionScores());
            teacherTotal = ScoreMath.weightedAverage(dimensions, context.rubric().weights());
        } else {
            if (command.dimensionScores() != null && !command.dimensionScores().isEmpty()) {
                throw invalid("dimension_scores", "总分模式不接受维度分");
            }
            if (command.teacherTotal() == null) {
                throw invalid("teacher_total", "总分模式必须填写教师总分");
            }
            teacherTotal = ScoreMath.requireScore(command.teacherTotal(), "teacher_total");
        }

        Instant now = clockProvider.now();
        TeacherScore existing = teacherScoreRepository.findBySubmissionId(submissionId)
                .orElse(null);
        TeacherScore saved;
        if (command.version() == 0) {
            if (existing != null) {
                throw conflict(submissionId);
            }
            TeacherScore created = new TeacherScore(
                    UUID.randomUUID(),
                    submissionId,
                    context.task().id(),
                    context.course().id(),
                    submission.studentAccountId(),
                    submission.classIdSnapshot(),
                    config.scoringMode(),
                    dimensions,
                    teacherTotal,
                    comment,
                    1,
                    teacher.accountId().value(),
                    now,
                    now);
            try {
                saved = teacherScoreRepository.insert(created);
            } catch (DataIntegrityViolationException exception) {
                throw conflict(submissionId);
            }
            if (!config.locked()) {
                configRepository.update(config.lockedBy(teacher.accountId().value(), now));
            }
        } else {
            if (existing == null || existing.version() != command.version()) {
                throw conflict(submissionId);
            }
            UUID classSnapshot = submission.classIdSnapshot() != null
                    ? submission.classIdSnapshot() : existing.classIdSnapshot();
            TeacherScore updated = new TeacherScore(
                    existing.id(),
                    existing.submissionId(),
                    existing.taskId(),
                    existing.courseId(),
                    existing.studentAccountId(),
                    classSnapshot,
                    config.scoringMode(),
                    dimensions,
                    teacherTotal,
                    comment,
                    existing.version() + 1,
                    teacher.accountId().value(),
                    now,
                    now);
            if (!teacherScoreRepository.updateIfVersion(updated, command.version())) {
                throw conflict(submissionId);
            }
            saved = updated;
        }

        FinalScore finalScore = finalScoreService.recompute(submissionId);
        return new TeacherScoreSaveView(
                toTeacherRow(saved),
                finalScore == null ? null : toFinalRow(finalScore));
    }

    private Map<String, BigDecimal> validateDimensions(
            TaskContext context, Map<String, BigDecimal> submitted) {
        if (submitted == null) {
            submitted = Map.of();
        }
        Set<String> applicable = Set.copyOf(context.rubric().dimensions());
        for (String key : submitted.keySet()) {
            if (!applicable.contains(key)) {
                throw invalid("dimension_scores", "维度 " + key + " 不适用于该任务");
            }
        }
        LinkedHashMap<String, BigDecimal> values = new LinkedHashMap<>();
        for (String dimension : context.rubric().dimensions()) {
            BigDecimal raw = submitted.get(dimension);
            int weight = context.rubric().weightOf(dimension);
            if (raw == null) {
                if (weight > 0) {
                    throw invalid("dimension_scores", "维度 " + dimension + " 必须填写");
                }
                continue;
            }
            values.put(dimension, ScoreMath.requireScore(raw, dimension));
        }
        return values;
    }

    private static String validateComment(String comment) {
        if (comment == null) {
            return null;
        }
        if (comment.length() > MAX_COMMENT_LENGTH) {
            throw invalid("comment", "评语最多 1000 字");
        }
        return comment;
    }

    static TeacherScoreRow toTeacherRow(TeacherScore score) {
        return new TeacherScoreRow(
                score.id(), score.scoringMode().wireValue(), score.dimensionScores(),
                score.teacherTotal(), score.comment(), score.version(), score.gradedBy(),
                score.gradedAt());
    }

    static FinalScoreRow toFinalRow(FinalScore score) {
        return new FinalScoreRow(
                score.status().wireValue(), score.aiTotal(), score.teacherTotal(),
                score.finalTotal(), score.finalDimensions(), score.errorCode(),
                score.computedAt());
    }

    private static M7Exception invalid(String field, String message) {
        return new M7Exception(M7ErrorCode.TEACHER_SCORE_INVALID, message,
                Map.of("field", field));
    }

    private static M7Exception conflict(UUID submissionId) {
        return new M7Exception(M7ErrorCode.TEACHER_SCORE_CONFLICT,
                "教师评分已被其他教师修改，请刷新后重试",
                Map.of("submission_id", submissionId));
    }

    /** Keeps the mutable map unmodifiable when a caller builds a command. */
    public static Map<String, BigDecimal> immutableDimensions(
            Map<String, BigDecimal> dimensions) {
        return dimensions == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
    }
}
