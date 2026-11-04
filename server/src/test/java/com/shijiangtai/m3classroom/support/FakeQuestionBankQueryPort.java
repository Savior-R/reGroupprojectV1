package com.shijiangtai.m3classroom.support;

import com.shijiangtai.m3classroom.application.port.QuestionBankQueryPort;
import com.shijiangtai.m3classroom.domain.task.PublishQuestion;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeQuestionBankQueryPort implements QuestionBankQueryPort {
    private final Map<UUID, PublishQuestion> questions = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    public void addQuestion(PublishQuestion question) {
        questions.put(question.questionId(), question);
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public List<PublishQuestion> getForPublish(SchoolId schoolId, List<QuestionId> questionIds) {
        if (!available) {
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "题库不可用");
        }
        List<PublishQuestion> result = new ArrayList<>();
        for (QuestionId questionId : questionIds) {
            PublishQuestion question = questions.get(questionId.value());
            if (question != null) {
                result.add(question);
            }
        }
        return result;
    }
}
