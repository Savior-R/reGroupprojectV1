package com.shijiangtai.m2course.domain;

/** 学生加入课程的方式，对应 course_students.join_method。 */
public enum JoinMethod {
    SELF,
    CLASS,
    ACCOUNT,
    ADMIN;

    public static JoinMethod fromCode(String code) {
        return valueOf(code.toUpperCase(java.util.Locale.ROOT));
    }

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
