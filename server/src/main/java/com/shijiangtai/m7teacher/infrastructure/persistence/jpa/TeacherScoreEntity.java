package com.shijiangtai.m7teacher.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "teacher_scores", schema = "m7_teacher")
public class TeacherScoreEntity {

    @Id
    private UUID id;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "student_account_id", nullable = false)
    private UUID studentAccountId;

    @Column(name = "class_id_snapshot")
    private UUID classIdSnapshot;

    @Column(name = "scoring_mode", nullable = false, length = 16)
    private String scoringMode;

    @Column(name = "dimension_scores", columnDefinition = "jsonb")
    private String dimensionScores;

    @Column(name = "teacher_total", nullable = false)
    private BigDecimal teacherTotal;

    @Column(length = 1000)
    private String comment;

    @Column(nullable = false)
    private int version;

    @Column(name = "graded_by", nullable = false)
    private UUID gradedBy;

    @Column(name = "graded_at", nullable = false)
    private Instant gradedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

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

    public UUID getClassIdSnapshot() {
        return classIdSnapshot;
    }

    public void setClassIdSnapshot(UUID classIdSnapshot) {
        this.classIdSnapshot = classIdSnapshot;
    }

    public String getScoringMode() {
        return scoringMode;
    }

    public void setScoringMode(String scoringMode) {
        this.scoringMode = scoringMode;
    }

    public String getDimensionScores() {
        return dimensionScores;
    }

    public void setDimensionScores(String dimensionScores) {
        this.dimensionScores = dimensionScores;
    }

    public BigDecimal getTeacherTotal() {
        return teacherTotal;
    }

    public void setTeacherTotal(BigDecimal teacherTotal) {
        this.teacherTotal = teacherTotal;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public UUID getGradedBy() {
        return gradedBy;
    }

    public void setGradedBy(UUID gradedBy) {
        this.gradedBy = gradedBy;
    }

    public Instant getGradedAt() {
        return gradedAt;
    }

    public void setGradedAt(Instant gradedAt) {
        this.gradedAt = gradedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
