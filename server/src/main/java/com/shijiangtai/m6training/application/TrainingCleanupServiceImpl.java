package com.shijiangtai.m6training.application;

import com.shijiangtai.m3classroom.application.port.MaterialObjectStorage;
import com.shijiangtai.m6training.application.event.TrainingRecordCleanupRequestedEvent;
import com.shijiangtai.m6training.domain.TrainingMaterial;
import com.shijiangtai.m6training.domain.TrainingMaterialRepository;
import com.shijiangtai.m6training.domain.TrainingRecord;
import com.shijiangtai.m6training.domain.TrainingRecordRepository;
import com.shijiangtai.shared.application.ClockProvider;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class TrainingCleanupServiceImpl implements TrainingCleanupService {
    private static final Logger log = LoggerFactory.getLogger(TrainingCleanupServiceImpl.class);
    private static final int BATCH = 100;

    private final TrainingRecordRepository recordRepository;
    private final TrainingMaterialRepository materialRepository;
    private final TrainingRecordCleanupExecutor cleanupExecutor;
    private final MaterialObjectStorage materialStorage;
    private final ApplicationEventPublisher eventPublisher;
    private final ClockProvider clock;

    public TrainingCleanupServiceImpl(
            TrainingRecordRepository recordRepository,
            TrainingMaterialRepository materialRepository,
            TrainingRecordCleanupExecutor cleanupExecutor,
            MaterialObjectStorage materialStorage,
            ApplicationEventPublisher eventPublisher,
            ClockProvider clock) {
        this.recordRepository = recordRepository;
        this.materialRepository = materialRepository;
        this.cleanupExecutor = cleanupExecutor;
        this.materialStorage = materialStorage;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    public void expireDueRecords() {
        Instant now = clock.now();
        List<TrainingRecord> expired = recordRepository.listActiveExpired(now, BATCH);
        for (TrainingRecord record : expired) {
            record.markExpired(now);
            recordRepository.save(record);
            eventPublisher.publishEvent(new TrainingRecordCleanupRequestedEvent(record.id()));
        }
    }

    @Override
    public void cleanupPendingRecords() {
        for (TrainingRecord record : recordRepository.listPendingDeletion(BATCH)) {
            try {
                cleanupExecutor.cleanupRecord(record.id());
            } catch (RuntimeException exception) {
                log.warn("Retrying training record cleanup for {}", record.id(), exception);
            }
        }
    }

    @Override
    public void expireUnusedMaterials() {
        Instant now = clock.now();
        for (TrainingMaterial material
                : materialRepository.listExpiredUploaded(now, BATCH)) {
            try {
                materialStorage.delete(material.objectKey());
                material.markExpired();
                materialRepository.save(material);
            } catch (RuntimeException exception) {
                log.warn("Retrying training material expiry for {}", material.id(), exception);
            }
        }
    }
}
