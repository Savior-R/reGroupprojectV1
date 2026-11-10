package com.shijiangtai.m6training.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for training session metadata. */
public interface TrainingSessionMetaRepository {

    Optional<TrainingSessionMeta> findBySessionId(UUID sessionId);

    Map<UUID, TrainingSessionMeta> findBySessionIds(Collection<UUID> sessionIds);

    TrainingSessionMeta save(TrainingSessionMeta meta);

    List<UUID> listPreparingSessionIdsByTemplate(UUID templateId);

    /** Active training sessions whose max duration has elapsed. */
    List<TrainingSessionDeadline> listInProgressPastDeadline(Instant now, int limit);

    /** Session ids whose meta references the template (for unpublish cancellation). */
    Optional<UUID> findTemplateIdBySession(UUID sessionId);

    void deleteBySessionId(UUID sessionId);

    record TrainingSessionDeadline(UUID sessionId, Instant startedAt, int maxSeconds) {
    }
}
