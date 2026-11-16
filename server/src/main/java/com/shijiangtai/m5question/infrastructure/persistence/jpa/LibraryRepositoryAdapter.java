package com.shijiangtai.m5question.infrastructure.persistence.jpa;

import com.shijiangtai.m5question.domain.Library;
import com.shijiangtai.m5question.domain.LibraryRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class LibraryRepositoryAdapter implements LibraryRepository {
    private final LibraryJpaRepository repository;

    public LibraryRepositoryAdapter(LibraryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<Library> findBySchoolId(UUID schoolId) {
        return repository.findBySchoolId(schoolId).map(this::toDomain);
    }

    @Override
    public Library save(Library library) {
        LibraryEntity entity = new LibraryEntity();
        entity.setId(library.id());
        entity.setSchoolId(library.schoolId());
        entity.setSeedVersion(library.seedVersion());
        entity.setCreatedAt(library.createdAt());
        entity.setUpdatedAt(library.updatedAt());
        repository.save(entity);
        return library;
    }

    private Library toDomain(LibraryEntity entity) {
        return Library.rehydrate(
                entity.getId(),
                entity.getSchoolId(),
                entity.getSeedVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
