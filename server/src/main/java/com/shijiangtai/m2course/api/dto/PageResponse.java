package com.shijiangtai.m2course.api.dto;

import com.shijiangtai.shared.domain.PageResult;
import java.util.List;

/** 业务列表分页响应：{items, total, page, size}。 */
public record PageResponse<T>(List<T> items, long total, int page, int size) {

    public static <T> PageResponse<T> of(PageResult<T> result) {
        return new PageResponse<>(result.items(), result.total(), result.page(), result.size());
    }
}
