package com.shijiangtai.m2course.application.view;

import java.util.UUID;

/** 老师组成员简要信息；用户名为真实姓名。 */
public record TeacherBrief(UUID accountId, String username) {
}
