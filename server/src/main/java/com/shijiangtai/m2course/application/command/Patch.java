package com.shijiangtai.m2course.application.command;

/**
 * PATCH 语义的三态包装：未传字段（absent）、显式传值、显式传 null（清空）。
 */
public record Patch<T>(boolean present, T value) {

    public static <T> Patch<T> absent() {
        return new Patch<>(false, null);
    }

    public static <T> Patch<T> of(T value) {
        return new Patch<>(true, value);
    }

    public static <T> Patch<T> ofNullable(T value) {
        return new Patch<>(true, value);
    }

    /** 未传时返回兜底值。 */
    public T orElse(T fallback) {
        return present ? value : fallback;
    }
}
