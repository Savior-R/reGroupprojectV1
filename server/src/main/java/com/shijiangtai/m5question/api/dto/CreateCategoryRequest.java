package com.shijiangtai.m5question.api.dto;

/** {@code POST /api/v1/question-bank/categories}. */
public record CreateCategoryRequest(String name, Integer sortOrder) {
}
