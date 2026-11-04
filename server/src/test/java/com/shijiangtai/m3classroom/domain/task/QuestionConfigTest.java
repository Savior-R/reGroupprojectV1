package com.shijiangtai.m3classroom.domain.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.QuestionId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class QuestionConfigTest {

    @Test
    void acceptsZeroToTenQuestions() {
        assertThat(new QuestionConfig(List.of(), 300).questionIds()).isEmpty();
        assertThat(configWithQuestionCount(10).questionIds()).hasSize(10);
    }

    @Test
    void rejectsElevenQuestions() {
        assertThatThrownBy(() -> configWithQuestionCount(11))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsDuplicates() {
        QuestionId id = new QuestionId(UUID.randomUUID());
        assertThatThrownBy(() -> new QuestionConfig(List.of(id, id), 300))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsTotalOutOfRange() {
        assertThatThrownBy(() -> new QuestionConfig(List.of(), 0))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new QuestionConfig(List.of(), 3601))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void copiesQuestionIds() {
        List<QuestionId> ids = new ArrayList<>();
        ids.add(new QuestionId(UUID.randomUUID()));
        QuestionConfig config = new QuestionConfig(ids, 300);
        ids.clear();
        assertThat(config.questionIds()).hasSize(1);
        assertThatThrownBy(() -> config.questionIds().add(new QuestionId(UUID.randomUUID())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private QuestionConfig configWithQuestionCount(int count) {
        List<QuestionId> ids = IntStream.range(0, count)
                .mapToObj(i -> new QuestionId(UUID.randomUUID()))
                .toList();
        return new QuestionConfig(ids, 300);
    }
}
