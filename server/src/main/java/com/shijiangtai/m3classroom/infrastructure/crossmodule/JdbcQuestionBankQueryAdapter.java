package com.shijiangtai.m3classroom.infrastructure.crossmodule;

import com.shijiangtai.m3classroom.application.port.QuestionBankQueryPort;
import com.shijiangtai.m3classroom.domain.task.PublishQuestion;
import com.shijiangtai.shared.domain.QuestionId;
import com.shijiangtai.shared.domain.SchoolId;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M5 question read for task publishing under docker/demo.
 *
 * <p>The in-memory adapter is still used by dev/test; this one resolves the real question bank
 * rows so interview tasks can be published against demo or production data.
 */
@Component
@Profile("docker")
public class JdbcQuestionBankQueryAdapter implements QuestionBankQueryPort {

    private final JdbcTemplate jdbcTemplate;

    public JdbcQuestionBankQueryAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<PublishQuestion> getForPublish(SchoolId schoolId, List<QuestionId> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(questionIds.size(), "?"));
        Object[] args = new Object[questionIds.size() + 1];
        args[0] = schoolId.value();
        for (int index = 0; index < questionIds.size(); index++) {
            args[index + 1] = questionIds.get(index).value();
        }
        return jdbcTemplate.query("""
                SELECT q.id, q.stem, q.answer_points, q.category_id, c.name,
                       q.school_id, q.status
                FROM m5_question.questions q
                JOIN m5_question.categories c ON c.id = q.category_id
                WHERE q.school_id = ? AND q.id IN (%s)
                """.formatted(placeholders),
                (rs, rowNum) -> new PublishQuestion(
                        rs.getObject("id", UUID.class),
                        rs.getString("stem"),
                        rs.getString("answer_points"),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("name"),
                        rs.getObject("school_id", UUID.class),
                        "active".equals(rs.getString("status"))),
                args);
    }
}
