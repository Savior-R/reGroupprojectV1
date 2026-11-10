package com.shijiangtai.m5question.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.m5question.domain.Question;
import com.shijiangtai.m5question.domain.SeedCatalog;
import com.shijiangtai.m5question.infrastructure.seed.SeedCatalogLoader;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * M5 module smoke (not the full M5-T01—08 suite): seed catalog contract, answer-point
 * invariant and the student 403 gate. Runs without Spring or PostgreSQL.
 */
class M5ModuleSmokeTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void seedCatalogLoadsAndEveryQuestionHasAnswerPoints() {
        SeedCatalogLoader loader = new SeedCatalogLoader(mapper);

        SeedCatalog catalog = loader.load();

        assertThat(catalog.seedVersion()).isNotBlank();
        assertThat(catalog.categories()).hasSize(3);
        assertThat(catalog.questions()).hasSize(6);
        Set<String> categoryNames = catalog.categories().stream()
                .map(SeedCatalog.SeedCategory::name)
                .collect(Collectors.toSet());
        assertThat(catalog.questions()).allSatisfy(question -> {
            assertThat(question.stem()).isNotBlank();
            assertThat(question.answerPoints()).isNotBlank();
            assertThat(categoryNames).contains(question.category());
        });
        assertThat(loader.load()).isSameAs(catalog);
    }

    @Test
    void questionRejectsMissingOrBlankAnswerPoints() {
        UUID schoolId = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        Instant now = Instant.now();
        UUID categoryId = UUID.randomUUID();

        assertThatThrownBy(() -> Question.create(UUID.randomUUID(), schoolId, categoryId,
                "请谈谈你的教育理念", "  ", creator, now))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo(ErrorCode.ANSWER_POINTS_MISSING);
        assertThatThrownBy(() -> Question.create(UUID.randomUUID(), schoolId, categoryId,
                "请谈谈你的教育理念", null, creator, now))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo(ErrorCode.ANSWER_POINTS_MISSING);
    }

    @Test
    void questionCreateTrimsContentAndStartsActive() {
        Question question = Question.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "  请谈谈你的教育理念  ", "  以学生发展为本  ", UUID.randomUUID(), Instant.now());

        assertThat(question.stem()).isEqualTo("请谈谈你的教育理念");
        assertThat(question.answerPoints()).isEqualTo("以学生发展为本");
        assertThat(question.isActive()).isTrue();
        assertThat(question.hasAnswerPoints()).isTrue();
    }

    @Test
    void studentsAreDeniedQuestionBankAccess() {
        CurrentUser student = new CurrentUser(new AccountId(UUID.randomUUID()),
                new SchoolId(UUID.randomUUID()), AccountType.STUDENT);
        CurrentUser teacher = new CurrentUser(new AccountId(UUID.randomUUID()),
                new SchoolId(UUID.randomUUID()), AccountType.TEACHER);

        assertThatThrownBy(() -> M5Access.requireReader(student))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo(ErrorCode.ROLE_NOT_ALLOWED);
        assertThat(M5Access.requireReader(teacher)).isSameAs(teacher);
        assertThat(M5Access.requireManager(teacher)).isSameAs(teacher);
    }
}
