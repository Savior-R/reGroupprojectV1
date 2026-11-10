package com.shijiangtai.m2course.application.command;

import java.time.Instant;

/**
 * 编辑课程命令。
 *
 * <p>学校、专业范围创建后不可修改；只能改名称与 end_at。两个字段均为 PATCH 语义：
 * 未传表示不改，传 null 表示清空（仅 end_at 允许清空）。
 */
public record UpdateCourseCommand(Patch<String> name, Patch<Instant> endAt) {
}
