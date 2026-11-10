package com.shijiangtai.m5question.domain;

import java.util.List;

/**
 * Built-in seed question bank loaded from {@code classpath:seed/question-bank.json}.
 *
 * <p>The seed is copied into school-owned rows on first access; later seed upgrades
 * never touch existing schools (04 §4.4).
 */
public record SeedCatalog(
        String seedVersion,
        List<SeedCategory> categories,
        List<SeedQuestion> questions) {

    public SeedCatalog {
        categories = categories == null ? List.of() : List.copyOf(categories);
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    public record SeedCategory(String name, int sortOrder) {
    }

    public record SeedQuestion(String category, String stem, String answerPoints) {
    }
}
