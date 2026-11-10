package com.shijiangtai.m2course.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.shijiangtai.m2course.application.port.CourseMemberRepository;
import com.shijiangtai.m2course.application.port.CourseRepository;
import com.shijiangtai.m2course.domain.Course;
import com.shijiangtai.m2course.domain.CourseStudent;
import com.shijiangtai.m2course.domain.JoinMethod;
import com.shijiangtai.m2course.domain.MajorId;
import com.shijiangtai.m2course.domain.StudentStatus;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 课程学生成员 IT：软删除保留历史、重复加入新行、在课统计与「已加入」标记。 */
class CourseMemberRepositoryIT extends M2PostgresIT {
    private static final Instant FUTURE = Instant.parse("2030-01-01T00:00:00Z");

    @Autowired
    private CourseMemberRepository memberRepository;

    @Autowired
    private CourseRepository courseRepository;

    @Test
    void addStudentMarksEnrolledAndCounts() {
        CourseId courseId = newCourse("M2 学生-加入");
        AccountId student = new AccountId(STUDENT);

        memberRepository.addStudent(courseId, student, JoinMethod.SELF, Instant.now());

        assertThat(memberRepository.hasActiveStudent(courseId, student)).isTrue();
        assertThat(memberRepository.countActiveStudents(List.of(courseId))).containsEntry(courseId, 1L);
        assertThat(memberRepository.findActiveCourseIds(student, List.of(courseId)))
                .containsExactly(courseId);
    }

    @Test
    void leavingKeepsHistoryRowAndAllowsRejoin() {
        CourseId courseId = newCourse("M2 学生-退出再加入");
        AccountId student = new AccountId(STUDENT);
        memberRepository.addStudent(courseId, student, JoinMethod.SELF, Instant.now());
        memberRepository.markStudentStatus(courseId, student, StudentStatus.LEFT, Instant.now());

        assertThat(memberRepository.hasActiveStudent(courseId, student)).isFalse();
        assertThat(memberRepository.countActiveStudents(List.of(courseId))).doesNotContainKey(courseId);
        List<CourseStudent> history = memberRepository.findStudents(courseId, true);
        assertThat(history).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(StudentStatus.LEFT);
            assertThat(row.leftAt()).isNotNull();
        });
        assertThat(memberRepository.findStudents(courseId, false)).isEmpty();

        memberRepository.addStudent(courseId, student, JoinMethod.CLASS, Instant.now());

        assertThat(memberRepository.hasActiveStudent(courseId, student)).isTrue();
        List<CourseStudent> afterRejoin = memberRepository.findStudents(courseId, true);
        assertThat(afterRejoin).hasSize(2);
        assertThat(afterRejoin).filteredOn(row -> row.status() == StudentStatus.ACTIVE)
                .singleElement()
                .extracting(CourseStudent::joinMethod)
                .isEqualTo(JoinMethod.CLASS);
    }

    @Test
    void removingStudentIsSoftDelete() {
        CourseId courseId = newCourse("M2 学生-移出");
        AccountId student = new AccountId(STUDENT2);
        memberRepository.addStudent(courseId, student, JoinMethod.ACCOUNT, Instant.now());

        memberRepository.markStudentStatus(courseId, student, StudentStatus.REMOVED, Instant.now());

        assertThat(memberRepository.hasActiveStudent(courseId, student)).isFalse();
        assertThat(memberRepository.findStudents(courseId, true)).singleElement().satisfies(row ->
                assertThat(row.status()).isEqualTo(StudentStatus.REMOVED));
    }

    @Test
    void countAndJoinedFlagsAreScopedPerCourse() {
        CourseId first = newCourse("M2 学生-课程A");
        CourseId second = newCourse("M2 学生-课程B");
        memberRepository.addStudent(first, new AccountId(STUDENT), JoinMethod.SELF, Instant.now());
        memberRepository.addStudent(first, new AccountId(STUDENT2), JoinMethod.SELF, Instant.now());
        memberRepository.addStudent(second, new AccountId(STUDENT2), JoinMethod.ADMIN, Instant.now());

        assertThat(memberRepository.countActiveStudents(List.of(first, second)))
                .containsEntry(first, 2L)
                .containsEntry(second, 1L);
        assertThat(memberRepository.findActiveCourseIds(new AccountId(STUDENT), List.of(first, second)))
                .containsExactly(first);
        assertThat(memberRepository.hasActiveStudent(second, new AccountId(STUDENT))).isFalse();
    }

    private CourseId newCourse(String name) {
        Course course = Course.create(new CourseId(UUID.randomUUID()), new SchoolId(SCHOOL),
                new AccountId(TEACHER), name, List.of(new MajorId(MAJOR)), FUTURE, Instant.now());
        courseRepository.save(course);
        return course.id();
    }
}
