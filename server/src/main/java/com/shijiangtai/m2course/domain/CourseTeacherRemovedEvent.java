package com.shijiangtai.m2course.domain;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;

/** 教师被移出老师组集成事件，after-commit 发布；M6 收到后下架该教师在本课程发布的训练模板。 */
public record CourseTeacherRemovedEvent(CourseId courseId, AccountId accountId) {
}
