package com.shijiangtai.m3classroom.application.port;

import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.CourseStudentInfo;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.CourseId;
import java.util.List;
import java.util.Optional;

public interface CourseQueryPort {
    Optional<CourseInfo> findCourse(CourseId courseId);

    boolean isTeacherInGroup(CourseId courseId, AccountId teacherId);

    List<CourseStudentInfo> activeStudents(CourseId courseId);
}
