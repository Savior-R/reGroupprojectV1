package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionFrameJpaRepository extends JpaRepository<SessionFrameEntity, UUID> {
}
