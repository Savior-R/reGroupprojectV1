package com.shijiangtai.m2course.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m2course.application.command.CourseSearchCriteria;
import com.shijiangtai.m2course.application.port.CourseRepository;
import com.shijiangtai.m2course.domain.Course;
import com.shijiangtai.m2course.domain.CourseStatus;
import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** 课程聚合持久化 IT：聚合写回、名称唯一范围、软删除历史、行锁与检索条件。 */
class CourseRepositoryIT extends M2PostgresIT {
    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private CourseRepository courseRepository;

    @Test
    void saveAndReloadKeepsAggregateState() {
        Course course = newCourse("M2 聚合回读", MAJOR, FUTURE);
        course.addTeacher(new AccountId(OTHER_TEACHER), Instant.now());

        courseRepository.save(course);
        Course loaded = courseRepository.findById(course.id()).orElseThrow();

        assertThat(loaded.name()).isEqualTo("M2 聚合回读");
        assertThat(loaded.schoolId()).isEqualTo(new SchoolId(SCHOOL));
        assertThat(loaded.createdBy()).isEqualTo(new AccountId(TEACHER));
        assertThat(loaded.status()).isEqualTo(CourseStatus.ACTIVE);
        assertThat(loaded.endAt()).isEqualTo(FUTURE);
        assertThat(loaded.majorIds()).containsExactly(new MajorId(MAJOR));
        assertThat(loaded.activeTeacherCount()).isEqualTo(2);
    }

    @Test
    void existsNameInSchoolIgnoresOtherSchools() {
        courseRepository.save(newCourse("M2 名称唯一", MAJOR, null));

        assertThat(courseRepository.existsNameInSchool(new SchoolId(SCHOOL), "M2 名称唯一")).isTrue();
        assertThat(courseRepository.existsNameInSchool(new SchoolId(OTHER_SCHOOL), "M2 名称唯一")).isFalse();
    }

    @Test
    void removingTeacherKeepsHistoryRow() {
        Course course = newCourse("M2 老师组历史", MAJOR, null);
        course.addTeacher(new AccountId(OTHER_TEACHER), Instant.now());
        courseRepository.save(course);

        Course loaded = courseRepository.findById(course.id()).orElseThrow();
        loaded.removeTeacher(new AccountId(OTHER_TEACHER), Instant.now());
        courseRepository.save(loaded);

        Course reloaded = courseRepository.findById(course.id()).orElseThrow();
        assertThat(reloaded.activeTeacherCount()).isEqualTo(1);
        assertThat(reloaded.teachers()).hasSize(2);
        assertThat(reloaded.activeTeachers())
                .extracting(teacher -> teacher.accountId().value())
                .containsExactly(TEACHER);
    }

    @Test
    @Transactional
    void findByIdForUpdateLocksExistingCourse() {
        Course course = newCourse("M2 行锁", MAJOR, null);
        courseRepository.save(course);

        assertThat(courseRepository.findByIdForUpdate(course.id())).isPresent();
        assertThat(courseRepository.findByIdForUpdate(new CourseId(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void searchAppliesTeacherMajorAndArchiveFilters() {
        Course mine = courseRepository.save(newCourse("M2 检索-我在组", MAJOR, FUTURE));
        // 建课人自动进老师组，因此「他人组」必须由另一位教师创建，才能验证 teacherId 过滤。
        Course others = Course.create(new CourseId(UUID.randomUUID()), new SchoolId(SCHOOL),
                new AccountId(OUTSIDER_TEACHER), "M2 检索-他人组", List.of(new MajorId(MAJOR2)),
                FUTURE, Instant.now());
        courseRepository.save(others);
        Course archived = courseRepository.save(newCourse("M2 检索-已归档", MAJOR, FUTURE));
        archived.archive(Instant.now());
        courseRepository.save(archived);

        List<Course> byTeacher = courseRepository.search(criteria("M2 检索", false, null, null));
        assertThat(byTeacher).extracting(Course::name).containsExactly("M2 检索-我在组");

        // 学生视角：专业命中 + 含归档；includeArchived=false 时归档必须消失。
        List<Course> byMajor = courseRepository.search(
                criteria("M2 检索", true, null, new MajorId(MAJOR)));
        assertThat(byMajor).extracting(Course::name)
                .containsExactlyInAnyOrder("M2 检索-我在组", "M2 检索-已归档");
        assertThat(courseRepository.search(criteria("M2 检索", false, null, new MajorId(MAJOR))))
                .extracting(Course::name)
                .containsExactly("M2 检索-我在组");

        List<Course> withArchived = courseRepository.search(criteria("M2 检索", true, null, null));
        assertThat(withArchived).extracting(Course::name).contains("M2 检索-已归档");
    }

    @Test
    void searchHandlesEndedFilterAndCountMatches() {
        courseRepository.save(newCourse("M2 结束-进行中", MAJOR, FUTURE));
        courseRepository.save(newCourse("M2 结束-已过期", MAJOR, PAST));
        courseRepository.save(newCourse("M2 结束-无期限", MAJOR, null));

        CourseSearchCriteria notEnded = allCriteria("M2 结束", Boolean.FALSE);
        CourseSearchCriteria ended = allCriteria("M2 结束", Boolean.TRUE);

        assertThat(courseRepository.search(notEnded)).extracting(Course::name)
                .containsExactlyInAnyOrder("M2 结束-进行中", "M2 结束-无期限");
        assertThat(courseRepository.count(notEnded)).isEqualTo(2);
        assertThat(courseRepository.search(ended)).extracting(Course::name)
                .containsExactly("M2 结束-已过期");
        assertThat(courseRepository.count(ended)).isEqualTo(1);
    }

    @Test
    void archiveAndRestoreArePersisted() {
        Course course = courseRepository.save(newCourse("M2 归档状态", MAJOR, null));

        course.archive(Instant.parse("2026-11-10T01:00:00Z"));
        courseRepository.save(course);
        Course archived = courseRepository.findById(course.id()).orElseThrow();
        assertThat(archived.isArchived()).isTrue();
        assertThat(archived.archivedAt()).isEqualTo(Instant.parse("2026-11-10T01:00:00Z"));

        archived.restoreToActive(Instant.parse("2026-11-10T02:00:00Z"));
        courseRepository.save(archived);
        Course restored = courseRepository.findById(course.id()).orElseThrow();
        assertThat(restored.isArchived()).isFalse();
        assertThat(restored.archivedAt()).isNull();
    }

    private Course newCourse(String name, UUID majorId, Instant endAt) {
        return Course.create(new CourseId(UUID.randomUUID()), new SchoolId(SCHOOL),
                new AccountId(TEACHER), name, List.of(new MajorId(majorId)), endAt, Instant.now());
    }

    /** 教师「我的课程」视角：本组 + 未归档。 */
    private CourseSearchCriteria criteria(String keyword, boolean includeArchived, Boolean ended,
            MajorId visibleMajor) {
        return new CourseSearchCriteria(new SchoolId(SCHOOL), keyword, includeArchived, null, ended,
                new AccountId(TEACHER), visibleMajor, 1, 20);
    }

    private CourseSearchCriteria allCriteria(String keyword, Boolean ended) {
        return new CourseSearchCriteria(new SchoolId(SCHOOL), keyword, false, null, ended,
                null, null, 1, 20);
    }
}
