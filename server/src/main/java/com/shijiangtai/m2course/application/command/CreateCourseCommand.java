package com.shijiangtai.m2course.application.command;

import com.shijiangtai.m2course.domain.MajorId;
import java.time.Instant;
import java.util.List;

/** 创建课程命令：name + 专业范围（至少一个）+ 可选 end_at。 */
public record CreateCourseCommand(String name, List<MajorId> majorIds, Instant endAt) {
}
