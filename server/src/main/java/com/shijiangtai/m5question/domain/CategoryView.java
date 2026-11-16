package com.shijiangtai.m5question.domain;

import java.time.Instant;
import java.util.UUID;

/** Read model returned by the category APIs. */
public record CategoryView(
        UUID categoryId,
        String name,
        int sortOrder,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static CategoryView of(Category category) {
        return new CategoryView(
                category.id(),
                category.name(),
                category.sortOrder(),
                category.status().wireValue(),
                category.createdAt(),
                category.updatedAt());
    }
}
