package com.shijiangtai.m7teacher.domain;

import java.util.Optional;
import java.util.UUID;

public interface GradingConfigRepository {

    Optional<GradingConfig> findByTaskId(UUID taskId);

    GradingConfig insert(GradingConfig config);

    /**
     * Inserts the default configuration unless one already exists for the task.
     *
     * @return the effective configuration after the insert attempt
     */
    Optional<GradingConfig> insertIfAbsent(GradingConfig config);

    GradingConfig update(GradingConfig config);
}
