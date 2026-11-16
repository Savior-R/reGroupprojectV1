package com.shijiangtai.m2course.application.command;

/** 课程成员列表查询：是否包含历史成员、分页。 */
public record MemberQuery(boolean includeHistory, int page, int size) {

    public MemberQuery normalized() {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = size <= 0 ? CourseQuery.DEFAULT_SIZE : Math.min(size, CourseQuery.MAX_SIZE);
        return new MemberQuery(includeHistory, normalizedPage, normalizedSize);
    }
}
