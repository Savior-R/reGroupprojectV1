package com.shijiangtai.m5question.application.command;

/** Question list filters (04 §6). */
public record QuestionQuery(
        String categoryId, String status, String keyword, int page, int size) {
}
