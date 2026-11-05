package com.shijiangtai.m3classroom.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "submissions", schema = "m3_classroom")
public class SubmissionEntity {
    @Id
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "student_account_id", nullable = false)
    private UUID studentAccountId;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "class_id_snapshot")
    private UUID classIdSnapshot;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

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

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getClassIdSnapshot() {
        return classIdSnapshot;
    }

    public void setClassIdSnapshot(UUID classIdSnapshot) {
        this.classIdSnapshot = classIdSnapshot;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
