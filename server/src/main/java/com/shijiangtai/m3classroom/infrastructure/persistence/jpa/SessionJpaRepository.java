package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionJpaRepository extends JpaRepository<SessionEntity, UUID> {

    List<SessionEntity> findByStudentAccountIdAndTaskIdOrderByAttemptNoAsc(
            UUID studentAccountId, UUID taskId);

    List<SessionEntity> findByStudentAccountIdAndTaskIdAndStatusInOrderByAttemptNoDesc(
            UUID studentAccountId, UUID taskId, Collection<String> statuses);

    long countByStudentAccountIdAndTaskId(UUID studentAccountId, UUID taskId);
}
