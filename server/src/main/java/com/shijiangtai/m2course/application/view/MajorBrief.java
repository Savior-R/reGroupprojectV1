package com.shijiangtai.m2course.application.view;

import java.util.UUID;

/** 专业简要信息（名称来自 M1 只读查询）。 */
public record MajorBrief(UUID id, String name) {
}
