package com.shijiangtai.m6training.domain;

import com.shijiangtai.shared.domain.PageResult;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for training templates. */
public interface TrainingTemplateRepository {

    Optional<TrainingTemplate> findById(UUID id);

    Optional<TrainingTemplate> findByIdAndCreator(UUID id, UUID creatorId);

    boolean existsPublished(UUID sourceTaskId, UUID courseId, TrainingLevel trainingLevel);

    PageResult<TrainingTemplate> listByCreator(
            UUID creatorId, TrainingLevel trainingLevel, UUID courseId, int page, int size);

    /** Templates visible to a student: target course active, enrolled, not ended. */
    PageResult<UUID> listStudentVisibleIds(
            UUID studentAccountId, UUID schoolId, TrainingLevel trainingLevel,
            UUID courseId, int page, int size);

    List<TrainingTemplate> listPublished(int limit);

    TrainingTemplate save(TrainingTemplate template);
}
