package com.shijiangtai.m6training.application;

import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m3classroom.application.port.RecordingObjectStorage;
import com.shijiangtai.m6training.domain.TrainingRecord;
import com.shijiangtai.m6training.domain.TrainingRecordRepository;
import com.shijiangtai.m6training.domain.TrainingRecordStatus;
import com.shijiangtai.m6training.domain.TrainingSessionMeta;
import com.shijiangtai.m6training.domain.TrainingSessionMetaRepository;
import com.shijiangtai.m6training.domain.port.TrainingClassroomPort;
import com.shijiangtai.m6training.domain.port.TrainingEvaluationPort;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Physical cleanup for one deleted / expired record: MinIO objects first, then evaluation rows,
 * then the M3 session (which cascades meta, record and any remaining evaluation rows).
 */
@Service
public class TrainingRecordCleanupExecutor {
    private static final String FREE_MATERIAL_PREFIX = "training-materials/";

    private final TrainingRecordRepository recordRepository;
    private final TrainingSessionMetaRepository sessionMetaRepository;
    private final TrainingClassroomPort classroomPort;
    private final TrainingEvaluationPort evaluationPort;
    private final RecordingObjectStorage recordingStorage;
    private final MaterialObjectStorage materialStorage;

    public TrainingRecordCleanupExecutor(
            TrainingRecordRepository recordRepository,
            TrainingSessionMetaRepository sessionMetaRepository,
            TrainingClassroomPort classroomPort,
            TrainingEvaluationPort evaluationPort,
            RecordingObjectStorage recordingStorage,
            MaterialObjectStorage materialStorage) {
        this.recordRepository = recordRepository;
        this.sessionMetaRepository = sessionMetaRepository;
        this.classroomPort = classroomPort;
        this.evaluationPort = evaluationPort;
        this.recordingStorage = recordingStorage;
        this.materialStorage = materialStorage;
    }

    @Transactional
    public void cleanupRecord(java.util.UUID recordId) {
        Optional<TrainingRecord> found = recordRepository.findById(recordId);
        if (found.isEmpty()) {
            return;
        }
        TrainingRecord record = found.get();
        if (record.status() != TrainingRecordStatus.DELETED
                && record.status() != TrainingRecordStatus.EXPIRED) {
            return;
        }
        for (String objectKey : classroomPort.recordingObjectKeys(record.trainingSessionId())) {
            recordingStorage.delete(objectKey);
        }
        sessionMetaRepository.findBySessionId(record.trainingSessionId())
                .map(TrainingSessionMeta::effectiveConfig)
                .map(config -> config.referenceMaterial())
                .filter(material -> record.templateId() == null)
                .filter(material -> material.objectKey().startsWith(FREE_MATERIAL_PREFIX))
                .ifPresent(material -> materialStorage.delete(material.objectKey()));
        evaluationPort.deleteByRecord(recordId);
        classroomPort.delete(record.trainingSessionId());
    }
}
