package com.shijiangtai.m3classroom.application.command;

public record Patch<T>(boolean present, T value) {
    public static <T> Patch<T> absent() {
        return new Patch<>(false, null);
    }

    public static <T> Patch<T> of(T value) {
        return new Patch<>(true, value);
    }
}
