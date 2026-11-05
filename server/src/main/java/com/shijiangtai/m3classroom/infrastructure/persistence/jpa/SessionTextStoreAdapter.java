package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import com.shijiangtai.m3classroom.application.port.SessionTextStore;
import com.shijiangtai.m3classroom.domain.session.SessionText;
import com.shijiangtai.shared.domain.SessionId;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Unified teacher/student/examiner text stream backed by {@code m3_classroom.session_texts}. */
@Repository
public class SessionTextStoreAdapter implements SessionTextStore {
    private final SessionTextJpaRepository repository;

    public SessionTextStoreAdapter(SessionTextJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(SessionText text) {
        repository.save(toEntity(text));
    }

    @Override
    public void saveAll(List<SessionText> texts) {
        if (texts.isEmpty()) {
            return;
        }
        repository.saveAll(texts.stream().map(this::toEntity).toList());
    }

    @Override
    public List<SessionText> bySession(SessionId id, String speakerType) {
        List<SessionTextEntity> entities = speakerType == null
                ? repository.findBySessionIdOrderByAtMsAscIdAsc(id.value())
                : repository.findBySessionIdAndSpeakerTypeOrderByAtMsAscIdAsc(
                        id.value(), speakerType);
        return entities.stream().map(this::toDomain).toList();
    }

    private SessionTextEntity toEntity(SessionText text) {
        SessionTextEntity entity = new SessionTextEntity();
        entity.setId(text.id());
        entity.setSessionId(text.sessionId().value());
        entity.setSpeakerType(text.speakerType());
        entity.setSpeakerRef(text.speakerRef());
        entity.setSource(text.source());
        entity.setKind(text.kind());
        entity.setText(text.text());
        entity.setStartMs(text.startMs());
        entity.setEndMs(text.endMs());
        entity.setAtMs(text.atMs());
        entity.setConfidence(text.confidence());
        entity.setModelVersion(text.modelVersion());
        entity.setPromptVersion(text.promptVersion());
        entity.setStatus(text.status());
        return entity;
    }

    private SessionText toDomain(SessionTextEntity entity) {
        return new SessionText(
                entity.getId(),
                new SessionId(entity.getSessionId()),
                entity.getSpeakerType(),
                entity.getSpeakerRef(),
                entity.getSource(),
                entity.getKind(),
                entity.getText(),
                entity.getStartMs(),
                entity.getEndMs(),
                entity.getAtMs(),
                entity.getConfidence(),
                entity.getModelVersion(),
                entity.getPromptVersion(),
                entity.getStatus());
    }
}
