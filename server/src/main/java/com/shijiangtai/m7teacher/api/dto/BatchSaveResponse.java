package com.shijiangtai.m7teacher.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shijiangtai.m7teacher.application.view.BatchRowView;
import com.shijiangtai.m7teacher.application.view.BatchSaveView;
import com.shijiangtai.m7teacher.application.view.FinalScoreRow;
import com.shijiangtai.m7teacher.application.view.TeacherScoreRow;
import java.util.List;
import java.util.UUID;

public record BatchSaveResponse(int successCount, int failureCount, List<Row> results) {

    public static BatchSaveResponse from(BatchSaveView view) {
        return new BatchSaveResponse(view.successCount(), view.failureCount(),
                view.results().stream().map(Row::from).toList());
    }

    public record Row(
            UUID submissionId,
            boolean ok,
            TeacherScoreRow teacherScore,
            @JsonProperty("final") FinalScoreRow finalScore,
            String errorCode,
            String message) {

        static Row from(BatchRowView view) {
            return new Row(view.submissionId(), view.ok(), view.teacherScore(),
                    view.finalScore(), view.errorCode(), view.message());
        }
    }
}
