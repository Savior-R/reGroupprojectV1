package com.shijiangtai.m2course.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 专业 ID。
 *
 * <p>M1 拥有专业（m1_auth.majors），shared 未提供 MajorId，M2 以本地值对象只读引用，
 * 不跨模块写入专业数据。
 */
public record MajorId(UUID value) {
    public MajorId {
        Objects.requireNonNull(value, "value");
    }

    public static MajorId of(UUID value) {
        return new MajorId(value);
    }
}
