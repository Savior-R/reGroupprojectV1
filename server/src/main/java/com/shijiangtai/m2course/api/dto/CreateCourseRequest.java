package com.shijiangtai.m2course.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** POST /api/v1/courses 请求体。 */
public record CreateCourseRequest(
        @NotBlank(message = "课程名称不能为空")
        @Size(max = 150, message = "课程名称不能超过 150 个字符")
        String name,

        @NotEmpty(message = "至少选择一个专业")
        List<UUID> majorIds,

        Instant endAt) {
}
