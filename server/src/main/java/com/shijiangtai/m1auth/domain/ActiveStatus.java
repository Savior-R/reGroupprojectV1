package com.shijiangtai.m1auth.domain;

/** 专业 / 班级 / 教师专业关系的启用状态（12 §3.1：active / inactive）。 */
public enum ActiveStatus {
    ACTIVE,
    INACTIVE;

    public String dbValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ActiveStatus fromDb(String value) {
        return "active".equalsIgnoreCase(value) ? ACTIVE : INACTIVE;
    }
}
