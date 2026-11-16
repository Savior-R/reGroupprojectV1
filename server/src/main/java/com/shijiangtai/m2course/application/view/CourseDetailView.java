package com.shijiangtai.m2course.application.view;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 课程详情视图，含课程状态与当前用户可执行的动作标记。
 */
public record CourseDetailView(
        UUID id,
        String name,
        UUID schoolId,
        UUID createdBy,
        String status,
        boolean ended,
        Instant endAt,
        Instant archivedAt,
        Instant createdAt,
        List<MajorBrief> majors,
        List<TeacherBrief> teachers,
        long studentCount,
        boolean joined,
        boolean canManage,
        boolean canJoin,
        boolean canLeave,
        boolean canArchive,
        boolean canRestore) {
}
