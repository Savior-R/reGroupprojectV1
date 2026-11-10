package com.shijiangtai.m2course.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M2 聚合与状态矩阵的领域单测（本轮先覆盖建课、老师组、end_at、归档最小闭环）。 */
class CourseTest {
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
    private static final SchoolId SCHOOL = new SchoolId(UUID.randomUUID());
    private static final AccountId TEACHER = new AccountId(UUID.randomUUID());

    @Test
    void createCoursePutsCreatorIntoTeacherGroup() {
        Course course = Course.create(
                new CourseId(UUID.randomUUID()), SCHOOL, TEACHER, "  2026 级语文教学论 ",
                List.of(new MajorId(UUID.randomUUID())), null, NOW);

        assertThat(course.name()).isEqualTo("2026 级语文教学论");
        assertThat(course.status()).isEqualTo(CourseStatus.ACTIVE);
        assertThat(course.hasActiveTeacher(TEACHER)).isTrue();
        assertThat(course.activeTeacherCount()).isEqualTo(1);
    }

    @Test
    void createCourseWithoutMajorIsRejected() {
        assertThatThrownBy(() -> Course.create(
                new CourseId(UUID.randomUUID()), SCHOOL, TEACHER, "课程", List.of(), null, NOW))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void removingLastTeacherIsRejected() {
        Course course = Course.create(
                new CourseId(UUID.randomUUID()), SCHOOL, TEACHER, "课程",
                List.of(new MajorId(UUID.randomUUID())), null, NOW);

        assertThatThrownBy(() -> course.removeTeacher(TEACHER, NOW))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo(ErrorCode.LAST_TEACHER);
    }

    @Test
    void addingSameTeacherTwiceIsRejected() {
        Course course = Course.create(
                new CourseId(UUID.randomUUID()), SCHOOL, TEACHER, "课程",
                List.of(new MajorId(UUID.randomUUID())), null, NOW);

        assertThatThrownBy(() -> course.addTeacher(TEACHER, NOW))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void endedCourseIsReadOnly() {
        Course course = Course.create(
                new CourseId(UUID.randomUUID()), SCHOOL, TEACHER, "课程",
                List.of(new MajorId(UUID.randomUUID())), NOW.plusSeconds(60), NOW);

        assertThat(course.isEnded(NOW)).isFalse();
        assertThat(course.isEnded(NOW.plusSeconds(60))).isTrue();
        assertThatThrownBy(() -> course.rename("改名", NOW.plusSeconds(60)))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo(ErrorCode.COURSE_ENDED);
    }

    @Test
    void archivedCourseIsHiddenFromStudentsAndReadOnly() {
        Course course = Course.create(
                new CourseId(UUID.randomUUID()), SCHOOL, TEACHER, "课程",
                List.of(new MajorId(UUID.randomUUID())), null, NOW);
        MajorId major = course.majorIds().iterator().next();

        course.archive(NOW);
        assertThat(course.isArchived()).isTrue();
        assertThat(course.isVisibleToStudent(major)).isFalse();
        assertThatThrownBy(() -> course.addTeacher(new AccountId(UUID.randomUUID()), NOW))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo(ErrorCode.COURSE_ARCHIVED);

        course.restoreToActive(NOW);
        assertThat(course.isArchived()).isFalse();
        assertThat(course.isVisibleToStudent(major)).isTrue();
    }
}
