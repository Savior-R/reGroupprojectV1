package com.shijiangtai.m5question.api.dto;

/** {@code PATCH /api/v1/question-bank/categories/{id}}. */
public record UpdateCategoryRequest(String name, Integer sortOrder, String status) {
}
