package com.shijiangtai.m2course.application.port;

import com.shijiangtai.m2course.domain.CourseStudent;
import com.shijiangtai.m2course.domain.JoinMethod;
import com.shijiangtai.m2course.domain.StudentStatus;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 课程学生成员关系持久化端口。 */
public interface CourseMemberRepository {

    boolean hasActiveStudent(CourseId courseId, AccountId studentId);

    void addStudent(CourseId courseId, AccountId studentId, JoinMethod joinMethod, Instant now);

    void markStudentStatus(CourseId courseId, AccountId studentId, StudentStatus status, Instant now);

    List<CourseStudent> findStudents(CourseId courseId, boolean includeHistory);

    Map<CourseId, Long> countActiveStudents(Collection<CourseId> courseIds);

    /** 该学生在这批课程中处于在课状态的课程集合，用于列表「已加入」标记。 */
    Set<CourseId> findActiveCourseIds(AccountId studentId, Collection<CourseId> courseIds);
}
