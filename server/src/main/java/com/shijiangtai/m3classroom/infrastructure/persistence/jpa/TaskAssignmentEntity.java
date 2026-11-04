package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_assignments", schema = "m3_classroom")
public class TaskAssignmentEntity {
    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "student_account_id", nullable = false)
    private UUID studentAccountId;

    @Column(name = "class_id_snapshot")
    private UUID classIdSnapshot;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(nullable = false, length = 16)
    private String status;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getStudentAccountId() {
        return studentAccountId;
    }

    public void setStudentAccountId(UUID studentAccountId) {
        this.studentAccountId = studentAccountId;
    }

    public UUID getClassIdSnapshot() {
        return classIdSnapshot;
    }

    public void setClassIdSnapshot(UUID classIdSnapshot) {
        this.classIdSnapshot = classIdSnapshot;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
