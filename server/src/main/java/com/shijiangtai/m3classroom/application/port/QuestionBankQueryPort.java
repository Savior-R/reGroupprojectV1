package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.m3classroom.domain.task.PublishQuestion;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.List;

public interface QuestionBankQueryPort {
    List<PublishQuestion> getForPublish(SchoolId schoolId, List<QuestionId> questionIds);
}
