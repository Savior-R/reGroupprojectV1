package com.shijiangtai.m2course.domain;

import com.shijiangtai.shared.domain.CourseId;

/** 课程归档集成事件，after-commit 发布；M6 收到后自动下架该课程的训练模板。 */
public record CourseArchivedEvent(CourseId courseId) {
}
