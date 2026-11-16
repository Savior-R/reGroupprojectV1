package com.shijiangtai.m5question.application.command;

/** Create a category inside the caller's school. */
public record CreateCategoryCommand(String name, Integer sortOrder) {
}
