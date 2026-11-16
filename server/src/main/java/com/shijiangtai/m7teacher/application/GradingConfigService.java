package com.shijiangtai.m7teacher.application;

import com.shijiangtai.m7teacher.application.command.UpdateGradingConfigCommand;
import com.shijiangtai.m7teacher.application.port.UpstreamQueryPort;
import com.shijiangtai.m7teacher.application.view.ClearScoresView;
import com.shijiangtai.m7teacher.application.view.GradingConfigView;
import com.shijiangtai.m7teacher.domain.GradingConfig;
import com.shijiangtai.m7teacher.domain.GradingConfigRepository;
import com.shijiangtai.m7teacher.domain.M7ErrorCode;
import com.shijiangtai.m7teacher.domain.M7Exception;
import com.shijiangtai.m7teacher.domain.TeacherScoreRepository;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Task grading configuration: lazy default, lock on first score, clear/unlock, versioning
 * (07 §4 / FR-M7-01).
 */
@Service
public class GradingConfigService {

    private final GradingConfigRepository configRepository;
    private final TeacherScoreRepository teacherScoreRepository;
    private final UpstreamQueryPort upstream;
    private final M7AccessPolicy accessPolicy;
    private final FinalScoreService finalScoreService;
    private final ClockProvider clockProvider;

    public GradingConfigService(
            GradingConfigRepository configRepository,
            TeacherScoreRepository teacherScoreRepository,
            UpstreamQueryPort upstream,
            M7AccessPolicy accessPolicy,
            FinalScoreService finalScoreService,
            ClockProvider clockProvider) {
        this.configRepository = configRepository;
        this.teacherScoreRepository = teacherScoreRepository;
        this.upstream = upstream;
        this.accessPolicy = accessPolicy;
        this.finalScoreService = finalScoreService;
        this.clockProvider = clockProvider;
    }

    /** Lazily creates the 70/30 dimension default unless the course is archived (07 §4.1). */
    @Transactional
    public GradingConfigView get(CurrentUser user, UUID taskId) {
        TaskContext context = accessPolicy.requireTeacherTask(user, taskId);
        accessPolicy.requirePublished(context);
        GradingConfig config = configRepository.findByTaskId(taskId).orElse(null);
        if (config == null && !context.course().archived()) {
            config = createDefault(context, user.accountId().value());
        }
        if (config == null) {
            config = defaultConfig(context, user.accountId().value());
        }
        return toView(context, config, hasScores(taskId));
    }

    @Transactional
    public GradingConfigView update(
            CurrentUser user, UUID taskId, UpdateGradingConfigCommand command) {
        TaskContext context = accessPolicy.requireTeacherTask(user, taskId);
        accessPolicy.requireNotArchived(context);
        accessPolicy.requirePublished(context);
        GradingConfig.validate(command.aiWeight(), command.teacherWeight(), command.scoringMode());

        upstream.lockTask(taskId);
        GradingConfig existing = configRepository.findByTaskId(taskId).orElse(null);
        boolean hasScores = hasScores(taskId);
        Instant now = clockProvider.now();

        GradingConfig saved;
        if (existing == null) {
            if (command.configVersion() != 1) {
                throw versionConflict(taskId);
            }
            GradingConfig created = new GradingConfig(UUID.randomUUID(), taskId,
                    context.course().id(), command.aiWeight(), command.teacherWeight(),
                    command.scoringMode(), 1, null, user.accountId().value(), now, now);
            try {
                saved = configRepository.insert(created);
            } catch (DataIntegrityViolationException exception) {
                throw versionConflict(taskId);
            }
        } else {
            if (command.configVersion() != existing.configVersion()) {
                throw versionConflict(taskId);
            }
            if (existing.locked() || hasScores) {
                throw new M7Exception(M7ErrorCode.GRADING_CONFIG_LOCKED,
                        "首次评分后评分模式与权重已锁定，请先清空全部教师评分",
                        Map.of("task_id", taskId));
            }
            saved = configRepository.update(existing.updated(
                    command.aiWeight(), command.teacherWeight(), command.scoringMode(),
                    user.accountId().value(), now));
        }
        finalScoreService.recomputeTask(taskId);
        return toView(context, saved, hasScores(taskId));
    }

    @Transactional
    public ClearScoresView clearScores(CurrentUser user, UUID taskId, boolean confirm) {
        TaskContext context = accessPolicy.requireTeacherTask(user, taskId);
        accessPolicy.requireNotArchived(context);
        if (!confirm) {
            throw new M7Exception(M7ErrorCode.VALIDATION_ERROR, "需要二次确认才能清空评分",
                    Map.of("field", "confirm"));
        }
        upstream.lockTask(taskId);
        Instant now = clockProvider.now();
        int deleted = teacherScoreRepository.deleteByTaskId(taskId);
        GradingConfig config = configRepository.findByTaskId(taskId).orElse(null);
        if (config != null && config.locked()) {
            configRepository.update(config.unlocked(user.accountId().value(), now));
        }
        int recomputed = finalScoreService.recomputeTask(taskId);
        return new ClearScoresView(deleted, true, recomputed);
    }

    /** The configuration used by scoring/statistics; never writes. */
    public GradingConfig effectiveConfig(TaskContext context) {
        return configRepository.findByTaskId(context.task().id())
                .orElseGet(() -> defaultConfig(context, context.task().id()));
    }

    /**
     * Called while the task row lock is held; creates the default configuration on first use.
     */
    @Transactional
    public GradingConfig getOrCreateForUpdate(TaskContext context, CurrentUser user) {
        GradingConfig existing = configRepository.findByTaskId(context.task().id()).orElse(null);
        if (existing != null) {
            return existing;
        }
        return createDefault(context, user.accountId().value());
    }

    private GradingConfig createDefault(TaskContext context, UUID updatedBy) {
        // ON CONFLICT DO NOTHING avoids poisoning the transaction when two concurrent GETs
        // lazy-create the default, then re-read the winner.
        GradingConfig config = defaultConfig(context, updatedBy);
        try {
            return configRepository.insertIfAbsent(config).orElseThrow(
                    () -> new M7Exception(M7ErrorCode.DATA_INCONSISTENT,
                            "评分配置并发创建失败", Map.of("task_id", context.task().id())));
        } catch (DataIntegrityViolationException exception) {
            throw new M7Exception(M7ErrorCode.DATA_INCONSISTENT,
                    "评分配置无法创建，上游数据不一致",
                    Map.of("task_id", context.task().id()));
        }
    }

    private GradingConfig defaultConfig(TaskContext context, UUID updatedBy) {
        return GradingConfig.defaults(UUID.randomUUID(), context.task().id(),
                context.course().id(),
                updatedBy == null ? context.task().id() : updatedBy,
                clockProvider.now());
    }

    private boolean hasScores(UUID taskId) {
        return teacherScoreRepository.countByTaskId(taskId) > 0;
    }

    private GradingConfigView toView(
            TaskContext context, GradingConfig config, boolean hasScores) {
        boolean canEdit = !context.course().archived()
                && !config.locked()
                && !hasScores;
        return new GradingConfigView(
                context.task().id(),
                context.course().id(),
                context.task().mode(),
                context.rubric().dimensions(),
                context.rubric().weights(),
                config.aiWeight(),
                config.teacherWeight(),
                config.scoringMode().wireValue(),
                config.configVersion(),
                config.lockedAt(),
                canEdit,
                hasScores);
    }

    private static M7Exception versionConflict(UUID taskId) {
        return new M7Exception(M7ErrorCode.GRADING_CONFIG_VERSION_CONFLICT,
                "评分配置版本冲突，请刷新后重试", Map.of("task_id", taskId));
    }
}
