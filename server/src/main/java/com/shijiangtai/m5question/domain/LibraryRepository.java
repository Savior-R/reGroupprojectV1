package com.shijiangtai.m5question.domain;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for the per-school question-bank library. */
public interface LibraryRepository {

    Optional<Library> findBySchoolId(UUID schoolId);

    Library save(Library library);
}
