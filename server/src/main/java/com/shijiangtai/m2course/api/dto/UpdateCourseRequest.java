package com.shijiangtai.m2course.api.dto;

import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * PATCH /api/v1/courses/{course_id} 请求体。
 *
 * <p>学校与专业范围不可修改；未传（null）字段表示不改。end_at 传 null 视为不改。
 */
public record UpdateCourseRequest(
        @Size(max = 150, message = "课程名称不能超过 150 个字符")
        String name,

        Instant endAt) {
}
