package com.shijiangtai.m5question.application;

import com.shijiangtai.m5question.domain.LibraryRepository;
import com.shijiangtai.shared.domain.SchoolId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class SeedServiceImpl implements SeedService {
    private static final Logger log = LoggerFactory.getLogger(SeedServiceImpl.class);

    private final LibraryRepository libraryRepository;
    private final LibraryInitializer libraryInitializer;

    public SeedServiceImpl(
            LibraryRepository libraryRepository, LibraryInitializer libraryInitializer) {
        this.libraryRepository = libraryRepository;
        this.libraryInitializer = libraryInitializer;
    }

    @Override
    public void ensureLibrary(SchoolId schoolId) {
        if (libraryRepository.findBySchoolId(schoolId.value()).isPresent()) {
            return;
        }
        try {
            libraryInitializer.initialize(schoolId);
        } catch (DataIntegrityViolationException exception) {
            // A concurrent request created the library first; the unique index on
            // libraries.school_id is the idempotency backstop (04 §4.3).
            log.info("Question bank for school {} was initialized concurrently",
                    schoolId.value());
        }
    }

    @Override
    public void onSchoolCreated(SchoolId schoolId) {
        ensureLibrary(schoolId);
    }
}
