package com.shijiangtai.m5question.application.command;

/** Patch a category; null fields are left unchanged. */
public record UpdateCategoryCommand(String name, Integer sortOrder, String status) {
}
