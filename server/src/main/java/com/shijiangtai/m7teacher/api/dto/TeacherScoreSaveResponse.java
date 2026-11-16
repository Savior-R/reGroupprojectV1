package com.shijiangtai.m7teacher.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shijiangtai.m7teacher.application.view.FinalScoreRow;
import com.shijiangtai.m7teacher.application.view.TeacherScoreRow;
import com.shijiangtai.m7teacher.application.view.TeacherScoreSaveView;

public record TeacherScoreSaveResponse(
        TeacherScoreRow teacherScore,
        @JsonProperty("final") FinalScoreRow finalScore) {

    public static TeacherScoreSaveResponse from(TeacherScoreSaveView view) {
        return new TeacherScoreSaveResponse(view.teacherScore(), view.finalScore());
    }
}
