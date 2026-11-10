package com.shijiangtai.m6training.application;

import com.shijiangtai.m6training.application.command.SelectTrainingRecordCommand;
import com.shijiangtai.m6training.application.event.TrainingRecordCleanupRequestedEvent;
import com.shijiangtai.m6training.application.view.TrainingRecordSelectionView;
import com.shijiangtai.m6training.application.view.TrainingRecordView;
import com.shijiangtai.m6training.application.view.TrainingReportView;
import com.shijiangtai.m6training.domain.TrainingError;
import com.shijiangtai.m6training.domain.TrainingException;
import com.shijiangtai.m6training.domain.TrainingRecord;
import com.shijiangtai.m6training.domain.TrainingRecordRepository;
import com.shijiangtai.m6training.domain.TrainingRecordStatus;
import com.shijiangtai.m6training.domain.TrainingSessionMeta;
import com.shijiangtai.m6training.domain.TrainingSessionMetaRepository;
import com.shijiangtai.m6training.domain.TrainingSessionStatus;
import com.shijiangtai.m6training.domain.port.TrainingClassroomPort;
import com.shijiangtai.m6training.domain.port.TrainingClassroomPort.ClassroomSession;
import com.shijiangtai.m6training.domain.port.TrainingEvaluationPort;
import com.shijiangtai.m6training.domain.port.TrainingEvaluationPort.EvaluationReport;
import com.shijiangtai.m6training.domain.port.TrainingEvaluationPort.EvaluationState;
import com.shijiangtai.shared.application.ClockProvider;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.IdGenerator;
import com.shijiangtai.shared.domain.PageResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrainingRecordServiceImpl implements TrainingRecordService {
    private static final Set<String> TERMINAL =
            Set.of("completed", "partial", "failed");
    private static final Set<String> READY = Set.of("completed", "partial");

    private final TrainingRecordRepository recordRepository;
    private final TrainingSessionMetaRepository sessionMetaRepository;
    private final TrainingClassroomPort classroomPort;
    private final TrainingEvaluationPort evaluationPort;
    private final ApplicationEventPublisher eventPublisher;
    private final M6Access access;
    private final IdGenerator idGenerator;
    private final ClockProvider clock;

    public TrainingRecordServiceImpl(
            TrainingRecordRepository recordRepository,
            TrainingSessionMetaRepository sessionMetaRepository,
            TrainingClassroomPort classroomPort,
            TrainingEvaluationPort evaluationPort,
            ApplicationEventPublisher eventPublisher,
            M6Access access,
            IdGenerator idGenerator,
            ClockProvider clock) {
        this.recordRepository = recordRepository;
        this.sessionMetaRepository = sessionMetaRepository;
        this.classroomPort = classroomPort;
        this.evaluationPort = evaluationPort;
        this.eventPublisher = eventPublisher;
        this.access = access;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TrainingRecordSelectionView select(
            CurrentUser user, SelectTrainingRecordCommand command) {
        access.requireStudent(user);
        if (command.sessionId() == null) {
            throw new TrainingException(TrainingError.VALIDATION_ERROR,
                    "session_id 必填", Map.of("field", "session_id"));
        }
        UUID studentId = user.accountId().value();
        ClassroomSession session = classroomPort.find(command.sessionId())
                .orElseThrow(() -> new TrainingException(TrainingError.RESOURCE_NOT_FOUND,
                        "训练 session 不存在", Map.of("session_id", command.sessionId().toString())));
        if (!session.studentAccountId().equals(studentId)) {
            throw new TrainingException(TrainingError.FORBIDDEN,
                    "只能选中自己的训练", Map.of());
        }
        if (recordRepository.findBySessionId(command.sessionId()).isPresent()) {
            throw new TrainingException(TrainingError.DUPLICATE_RESOURCE,
                    "该训练已选中", Map.of("session_id", command.sessionId().toString()));
        }
        String sessionStatus = session.status();
        if (TrainingSessionStatus.SELECTED.wireValue().equals(sessionStatus)
                || TrainingSessionStatus.EVALUATED.wireValue().equals(sessionStatus)) {
            throw new TrainingException(TrainingError.DUPLICATE_RESOURCE,
                    "该训练已选中", Map.of("session_id", command.sessionId().toString()));
        }
        if (!TrainingSessionStatus.ENDED.wireValue().equals(sessionStatus)
                && !TrainingSessionStatus.REVIEWING.wireValue().equals(sessionStatus)) {
            throw new TrainingException(TrainingError.TRAINING_NOT_ELIGIBLE,
                    "只能选中已结束的训练", Map.of("status", sessionStatus));
        }
        if (!classroomPort.allStreamsUploaded(command.sessionId())) {
            throw new TrainingException(TrainingError.RECORDING_INCOMPLETE,
                    "三路录制尚未全部上传", Map.of("session_id", command.sessionId().toString()));
        }
        TrainingSessionMeta meta = sessionMetaRepository.findBySessionId(command.sessionId())
                .orElseThrow(() -> new TrainingException(TrainingError.RESOURCE_NOT_FOUND,
                        "训练配置不存在", Map.of()));

        Instant now = clock.now();
        recordRepository.lockGroup(studentId, meta.templateId());
        List<TrainingRecord> active = new ArrayList<>(
                recordRepository.listActiveByGroup(studentId, meta.templateId()).stream()
                        .filter(record -> record.expiresAt().isAfter(now))
                        .toList());
        UUID deletedRecordId = null;
        while (active.size() >= TrainingRecord.MAX_ACTIVE_PER_GROUP) {
            TrainingRecord oldest = active.removeFirst();
            oldest.markDeleted(now);
            recordRepository.save(oldest);
            if (deletedRecordId == null) {
                deletedRecordId = oldest.id();
            }
            eventPublisher.publishEvent(
                    new TrainingRecordCleanupRequestedEvent(oldest.id()));
        }

        TrainingRecord record = TrainingRecord.create(
                idGenerator.nextId(),
                command.sessionId(),
                studentId,
                meta.templateId(),
                now,
                now.plus(TrainingRecord.RETENTION_MONTHS, ChronoUnit.MONTHS));
        try {
            recordRepository.save(record);
        } catch (DataIntegrityViolationException exception) {
            throw new TrainingException(TrainingError.DUPLICATE_RESOURCE,
                    "该训练已选中", Map.of("session_id", command.sessionId().toString()));
        }
        classroomPort.updateStatus(
                command.sessionId(), TrainingSessionStatus.SELECTED.wireValue(), null, null);
        evaluationPort.requestEvaluation(new TrainingEvaluationPort.EvaluationRequest(
                record.id(),
                record.trainingSessionId(),
                studentId,
                meta.effectiveConfig().courseId(),
                meta.trainingLevel(),
                meta.effectiveConfig()));

        return new TrainingRecordSelectionView(
                toView(record, meta, Optional.empty(), now), deletedRecordId);
    }

    @Override
    @Transactional
    public PageResult<TrainingRecordView> list(
            CurrentUser user, String status, int page, int size) {
        access.requireStudent(user);
        TrainingRecordStatus parsedStatus =
                status == null || status.isBlank() ? null : TrainingRecordStatus.fromWire(status);
        Instant now = clock.now();
        PageResult<TrainingRecord> records = recordRepository.listByStudent(
                user.accountId().value(), parsedStatus, now, page, size);
        Map<UUID, TrainingSessionMeta> metas = sessionMetaRepository.findBySessionIds(
                records.items().stream().map(TrainingRecord::trainingSessionId).toList());
        List<TrainingRecordView> items = records.items().stream()
                .map(record -> {
                    TrainingSessionMeta meta = metas.get(record.trainingSessionId());
                    return meta == null ? null
                            : toView(record, meta, evaluationPort.findState(record.id()), now);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
        return new PageResult<>(items, records.total(), records.page(), records.size());
    }

    @Override
    @Transactional
    public TrainingRecordView get(CurrentUser user, UUID recordId) {
        TrainingRecord record = requireViewable(user, recordId);
        Instant now = clock.now();
        TrainingSessionMeta meta = requireMeta(record);
        Optional<EvaluationState> state = evaluationPort.findState(recordId);
        if (record.status() == TrainingRecordStatus.SELECTED
                && state.filter(value -> TERMINAL.contains(value.status())).isPresent()) {
            record.markEvaluated(now);
            recordRepository.save(record);
            classroomPort.updateStatus(
                    record.trainingSessionId(),
                    TrainingSessionStatus.EVALUATED.wireValue(), null, null);
        }
        return toView(record, meta, state, now);
    }

    @Override
    @Transactional
    public void delete(CurrentUser user, UUID recordId) {
        TrainingRecord record = requireViewable(user, recordId);
        record.markDeleted(clock.now());
        recordRepository.save(record);
        eventPublisher.publishEvent(new TrainingRecordCleanupRequestedEvent(record.id()));
    }

    @Override
    @Transactional
    public TrainingReportView getEvaluation(CurrentUser user, UUID recordId) {
        TrainingRecord record = requireViewable(user, recordId);
        EvaluationState state = evaluationPort.findState(recordId)
                .orElseThrow(() -> new TrainingException(
                        TrainingError.EVALUATION_NOT_READY, "评测尚未开始", Map.of()));
        if ("failed".equals(state.status())) {
            throw new TrainingException(TrainingError.EVALUATION_FAILED,
                    "训练评测失败", Map.of("record_id", recordId.toString()));
        }
        if (!READY.contains(state.status())) {
            throw new TrainingException(TrainingError.EVALUATION_NOT_READY,
                    "评测尚未完成", Map.of("status", state.status()));
        }
        EvaluationReport report = evaluationPort.findReport(recordId)
                .orElseThrow(() -> new TrainingException(
                        TrainingError.EVALUATION_NOT_READY, "评测报告尚未生成", Map.of()));
        return new TrainingReportView(
                recordId,
                report.evaluationId(),
                report.status(),
                report.totalScore(),
                report.dimensions(),
                report.issues(),
                report.warnings(),
                report.completedAt());
    }

    private TrainingRecord requireViewable(CurrentUser user, UUID recordId) {
        access.requireStudent(user);
        TrainingRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> recordNotFound(recordId));
        if (!record.studentAccountId().equals(user.accountId().value())) {
            throw new TrainingException(TrainingError.FORBIDDEN,
                    "只能查看自己的训练记录", Map.of("record_id", recordId.toString()));
        }
        if (!record.status().active() || !record.expiresAt().isAfter(clock.now())) {
            throw recordNotFound(recordId);
        }
        return record;
    }

    private TrainingSessionMeta requireMeta(TrainingRecord record) {
        return sessionMetaRepository.findBySessionId(record.trainingSessionId())
                .orElseThrow(() -> new TrainingException(TrainingError.RESOURCE_NOT_FOUND,
                        "训练配置不存在", Map.of("record_id", record.id().toString())));
    }

    private TrainingRecordView toView(
            TrainingRecord record,
            TrainingSessionMeta meta,
            Optional<EvaluationState> state,
            Instant now) {
        String evaluationStatus = state.map(EvaluationState::status).orElse("pending");
        boolean reportAvailable = READY.contains(evaluationStatus);
        long remainingDays = Math.max(0,
                ChronoUnit.DAYS.between(now, record.expiresAt()));
        return new TrainingRecordView(
                record.id(),
                record.trainingSessionId(),
                record.templateId(),
                meta.effectiveConfig().topic(),
                meta.trainingLevel(),
                record.selectedAt(),
                record.expiresAt(),
                record.effectiveStatus(now).wireValue(),
                evaluationStatus,
                state.map(EvaluationState::totalScore).orElse(null),
                reportAvailable,
                remainingDays);
    }

    private TrainingException recordNotFound(UUID recordId) {
        return new TrainingException(TrainingError.RESOURCE_NOT_FOUND,
                "训练记录不存在", Map.of("record_id", recordId.toString()));
    }
}
