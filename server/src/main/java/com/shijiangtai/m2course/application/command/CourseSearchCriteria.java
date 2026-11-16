package com.shijiangtai.m2course.application.command;

import com.shijiangtai.m2course.domain.CourseStatus;
import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.SchoolId;

/**
 * 仓储检索条件。角色过滤在应用层折算成条件，仓储不做权限判断。
 *
 * @param includeArchived true 时包含已归档课程（仅管理员列表）
 * @param status          课程状态筛选，null 表示不限
 * @param ended           是否已到 end_at：true 只看已结束，false 只看未结束，null 不限
 * @param teacherId       仅返回该教师所在老师组的课程（教师「我的课程」）
 * @param visibleMajorId  仅返回专业范围包含该专业的课程（学生可见性）
 */
public record CourseSearchCriteria(
        SchoolId schoolId,
        String keyword,
        boolean includeArchived,
        CourseStatus status,
        Boolean ended,
        AccountId teacherId,
        MajorId visibleMajorId,
        int page,
        int size) {

    public long offset() {
        return (long) (Math.max(page, 1) - 1) * Math.max(size, 1);
    }
}
