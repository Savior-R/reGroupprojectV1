package com.shijiangtai.m4eval.application.view;

import com.shijiangtai.m4eval.domain.EvaluationIssue;
import java.util.UUID;

public record IssueView(
        UUID issueId,
        String dimension,
        Long startMs,
        Long endMs,
        String severity,
        String evidence,
        String suggestion,
        boolean isSuggestion,
        Integer suggestionRank) {

    public static IssueView from(EvaluationIssue issue) {
        return new IssueView(
                issue.id(),
                issue.dimension(),
                issue.startMs(),
                issue.endMs(),
                issue.severity(),
                issue.evidence(),
                issue.suggestion(),
                issue.isSuggestion(),
                issue.suggestionRank());
    }
}
