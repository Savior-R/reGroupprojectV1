package com.shijiangtai.m3classroom.application.view;

import java.util.List;
import java.util.UUID;

public record StudentTaskView(
        UUID taskId,
        UUID courseId,
        String courseName,
        String mode,
        String topic,
        int durationSeconds,
        int questionCount,
        List<StudentVirtualStudentView> virtualStudents,
        List<UUID> teacherAccountIds,
        String status,
        String assignmentStatus) {
    public StudentTaskView {
        virtualStudents = List.copyOf(virtualStudents);
        teacherAccountIds = List.copyOf(teacherAccountIds);
    }
}
