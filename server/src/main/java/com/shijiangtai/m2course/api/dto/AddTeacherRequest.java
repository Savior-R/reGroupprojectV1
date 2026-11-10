package com.shijiangtai.m2course.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** POST /api/v1/courses/{course_id}/teachers 请求体。 */
public record AddTeacherRequest(@NotNull(message = "account_id 不能为空") UUID accountId) {
}
