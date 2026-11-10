package com.shijiangtai.m2course.domain;

/** 课程学生状态：在课 / 主动退出 / 被移出（软删除，保留历史）。 */
public enum StudentStatus {
    ACTIVE,
    LEFT,
    REMOVED
}
