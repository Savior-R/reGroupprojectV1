package com.shijiangtai.m2course.application.view;

import java.time.Instant;
import java.util.UUID;

/** 课程学生视图；status 为 active / left / removed。 */
public record CourseStudentView(
        UUID accountId,
        String username,
        UUID classId,
        String className,
        String joinMethod,
        String status,
        Instant joinedAt,
        Instant leftAt) {
}
