package com.shijiangtai.m3classroom.application.view;

import java.util.List;
import java.util.UUID;

public record QuestionConfigView(List<UUID> questionIds, int totalDurationSeconds) {
    public QuestionConfigView {
        questionIds = List.copyOf(questionIds);
    }
}
