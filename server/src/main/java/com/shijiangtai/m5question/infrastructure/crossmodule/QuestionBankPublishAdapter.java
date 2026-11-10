package com.shijiangtai.m5question.infrastructure.crossmodule;

import com.shijiangtai.m3classroom.application.port.QuestionBankQueryPort;
import com.shijiangtai.m3classroom.domain.task.PublishQuestion;
import com.shijiangtai.m5question.application.QuestionBankQueryService;
import com.shijiangtai.m5question.domain.QuestionView;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * M5 implementation of M3's publish-time question lookup (FR-M5-06).
 *
 * <p>Keeps the teacher's selection order so M3's snapshot stores the requested question
 * order. Disabled questions are returned with {@code enabled=false}; M3's publish policy
 * rejects them, which is also where answer-point and empty-bank validation lives.
 *
 * <p>{@code @Primary} resolves the overlap with M3's dev/test in-memory adapter; the
 * {@code test} profile keeps that adapter so M3 tests stay hermetic.
 */
@Component
@Primary
@Profile("!test")
public class QuestionBankPublishAdapter implements QuestionBankQueryPort {
    private final QuestionBankQueryService queryService;

    public QuestionBankPublishAdapter(QuestionBankQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public List<PublishQuestion> getForPublish(SchoolId schoolId, List<QuestionId> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = questionIds.stream().map(QuestionId::value).toList();
        Map<UUID, QuestionView> byId = new HashMap<>();
        for (QuestionView view : queryService.getQuestions(schoolId, ids)) {
            byId.put(view.questionId(), view);
        }
        List<PublishQuestion> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            QuestionView view = byId.get(id);
            if (view == null) {
                continue;
            }
            result.add(new PublishQuestion(
                    view.questionId(),
                    view.stem(),
                    view.answerPoints(),
                    view.categoryId(),
                    view.categoryName(),
                    schoolId.value(),
                    view.enabled()));
        }
        return result;
    }
}
