package com.shijiangtai.m7teacher.application.view;

import java.util.UUID;

public record GradingQuery(UUID classId, String status, String keyword, int page, int size) {

    public GradingQuery {
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        if (size > 100) {
            size = 100;
        }
    }
}
