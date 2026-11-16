package com.shijiangtai.m2course.application.view;

import java.time.Instant;
import java.util.UUID;

/** 老师组成员视图；status 为 active / removed。 */
public record CourseTeacherView(
        UUID accountId,
        String username,
        String status,
        Instant joinedAt,
        Instant leftAt) {
}
