package com.shijiangtai.m2course.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** POST /api/v1/courses/{course_id}/students/class 请求体。 */
public record AddStudentByClassRequest(@NotNull(message = "class_id 不能为空") UUID classId) {
}
