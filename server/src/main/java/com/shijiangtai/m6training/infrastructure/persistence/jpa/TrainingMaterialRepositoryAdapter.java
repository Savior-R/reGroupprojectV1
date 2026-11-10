package com.shijiangtai.m6training.infrastructure.persistence.jpa;

import com.shijiangtai.m6training.domain.TrainingMaterial;
import com.shijiangtai.m6training.domain.TrainingMaterialRepository;
import com.shijiangtai.m6training.domain.TrainingMaterialStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class TrainingMaterialRepositoryAdapter implements TrainingMaterialRepository {
    private final TrainingMaterialJpaRepository repository;

    public TrainingMaterialRepositoryAdapter(TrainingMaterialJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<TrainingMaterial> findByIdAndStudent(UUID id, UUID studentAccountId) {
        return repository.findByIdAndStudentAccountId(id, studentAccountId)
                .map(this::toDomain);
    }

    @Override
    public List<TrainingMaterial> listExpiredUploaded(Instant now, int limit) {
        return repository.findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                        TrainingMaterialStatus.UPLOADED.wireValue(),
                        now,
                        PageRequest.of(0, Math.max(1, limit))).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public TrainingMaterial save(TrainingMaterial material) {
        TrainingMaterialEntity entity =
                repository.findById(material.id()).orElseGet(TrainingMaterialEntity::new);
        entity.setId(material.id());
        entity.setStudentAccountId(material.studentAccountId());
        entity.setFilename(material.filename());
        entity.setMime(material.mime());
        entity.setSize(material.size());
        entity.setObjectKey(material.objectKey());
        entity.setParsedText(material.parsedText());
        entity.setCharCount(material.charCount());
        entity.setStatus(material.status().wireValue());
        entity.setExpiresAt(material.expiresAt());
        entity.setCreatedAt(material.createdAt());
        repository.save(entity);
        return material;
    }

    @Override
    public void delete(UUID id) {
        repository.deleteById(id);
    }

    private TrainingMaterial toDomain(TrainingMaterialEntity entity) {
        return TrainingMaterial.rehydrate(
                entity.getId(),
                entity.getStudentAccountId(),
                entity.getFilename(),
                entity.getMime(),
                entity.getSize(),
                entity.getObjectKey(),
                entity.getParsedText(),
                entity.getCharCount() == null ? 0 : entity.getCharCount(),
                TrainingMaterialStatus.fromWire(entity.getStatus()),
                entity.getExpiresAt(),
                entity.getCreatedAt());
    }
}
