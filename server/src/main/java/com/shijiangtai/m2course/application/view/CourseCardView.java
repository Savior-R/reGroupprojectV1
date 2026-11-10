package com.shijiangtai.m2course.application.view;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 课程卡片 / 列表行视图。
 *
 * @param status     active / archived
 * @param ended      是否已到 end_at（教学结束，仍可见）
 * @param joined     当前学生是否在课
 * @param canManage  当前用户是否可管理（本组教师 / 本校管理员）
 */
public record CourseCardView(
        UUID id,
        String name,
        String status,
        boolean ended,
        Instant endAt,
        List<MajorBrief> majors,
        List<TeacherBrief> teachers,
        long studentCount,
        boolean joined,
        boolean canManage) {
}
