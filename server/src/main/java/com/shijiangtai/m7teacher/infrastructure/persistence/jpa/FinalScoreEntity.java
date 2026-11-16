package com.shijiangtai.m7teacher.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "final_scores", schema = "m7_teacher")
public class FinalScoreEntity {

    @Id
    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "student_account_id", nullable = false)
    private UUID studentAccountId;

    @Column(name = "ai_evaluation_id")
    private UUID aiEvaluationId;

    @Column(name = "teacher_score_id")
    private UUID teacherScoreId;

    @Column(name = "ai_total")
    private BigDecimal aiTotal;

    @Column(name = "teacher_total")
    private BigDecimal teacherTotal;

    @Column(name = "final_total")
    private BigDecimal finalTotal;

    @Column(name = "final_dimensions", columnDefinition = "jsonb")
    private String finalDimensions;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "class_id_snapshot")
    private UUID classIdSnapshot;

    @Column(name = "config_version", nullable = false)
    private int configVersion;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    public UUID getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(UUID submissionId) {
        this.submissionId = submissionId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public void setCourseId(UUID courseId) {
        this.courseId = courseId;
    }

    public UUID getStudentAccountId() {
        return studentAccountId;
    }

    public void setStudentAccountId(UUID studentAccountId) {
        this.studentAccountId = studentAccountId;
    }

    public UUID getAiEvaluationId() {
        return aiEvaluationId;
    }

    public void setAiEvaluationId(UUID aiEvaluationId) {
        this.aiEvaluationId = aiEvaluationId;
    }

    public UUID getTeacherScoreId() {
        return teacherScoreId;
    }

    public void setTeacherScoreId(UUID teacherScoreId) {
        this.teacherScoreId = teacherScoreId;
    }

    public BigDecimal getAiTotal() {
        return aiTotal;
    }

    public void setAiTotal(BigDecimal aiTotal) {
        this.aiTotal = aiTotal;
    }

    public BigDecimal getTeacherTotal() {
        return teacherTotal;
    }

    public void setTeacherTotal(BigDecimal teacherTotal) {
        this.teacherTotal = teacherTotal;
    }

    public BigDecimal getFinalTotal() {
        return finalTotal;
    }

    public void setFinalTotal(BigDecimal finalTotal) {
        this.finalTotal = finalTotal;
    }

    public String getFinalDimensions() {
        return finalDimensions;
    }

    public void setFinalDimensions(String finalDimensions) {
        this.finalDimensions = finalDimensions;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getClassIdSnapshot() {
        return classIdSnapshot;
    }

    public void setClassIdSnapshot(UUID classIdSnapshot) {
        this.classIdSnapshot = classIdSnapshot;
    }

    public int getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(int configVersion) {
        this.configVersion = configVersion;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public void setComputedAt(Instant computedAt) {
        this.computedAt = computedAt;
    }
}
