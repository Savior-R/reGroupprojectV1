package com.shijiangtai.m2course.application.command;

/** 课程列表查询参数：关键字、范围（mine / all）、状态筛选、分页。 */
public record CourseQuery(String keyword, String scope, String status, int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public CourseQuery normalized() {
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new CourseQuery(trimToNull(keyword), trimToNull(scope), trimToNull(status),
                normalizedPage, normalizedSize);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
