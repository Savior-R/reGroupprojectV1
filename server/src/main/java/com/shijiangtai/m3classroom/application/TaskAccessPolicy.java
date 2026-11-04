package com.shijiangtai.m3classroom.application;

import com.shijiangtai.m3classroom.application.port.CourseQueryPort;
import com.shijiangtai.m3classroom.domain.task.CourseInfo;
import com.shijiangtai.m3classroom.domain.task.Task;
import com.shijiangtai.m3classroom.domain.task.TaskAssignment;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class TaskAccessPolicy {
    private final CourseQueryPort courseQueryPort;

    public TaskAccessPolicy(CourseQueryPort courseQueryPort) {
        this.courseQueryPort = Objects.requireNonNull(courseQueryPort, "courseQueryPort");
    }

    public CourseInfo requireTeacherCourse(CurrentUser user, CourseId courseId) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(courseId, "courseId");
        if (user.type() != AccountType.TEACHER) {
            throw new DomainException(ErrorCode.FORBIDDEN, "无权限访问任务");
        }
        CourseInfo course = courseQueryPort.findCourse(courseId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND,
                        "课程不存在", Map.of("course_id", courseId.value())));
        if (!course.schoolId().equals(user.schoolId().value())) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS,
                    "跨校访问", Map.of("course_id", courseId.value()));
        }
        if (!courseQueryPort.isTeacherInGroup(courseId, user.accountId())) {
            throw new DomainException(ErrorCode.TEACHER_NOT_IN_GROUP,
                    "不在课程老师组", Map.of("course_id", courseId.value()));
        }
        return course;
    }

    public CourseInfo requireTeacherTask(CurrentUser user, Task task) {
        Objects.requireNonNull(task, "task");
        return requireTeacherCourse(user, task.courseId());
    }

    public void requireStudentAssigned(
            CurrentUser user, CourseInfo course, TaskAssignment assignment) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(course, "course");
        if (user.type() != AccountType.STUDENT) {
            throw new DomainException(ErrorCode.FORBIDDEN, "无权限访问任务");
        }
        if (assignment == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "任务未分配给当前学生");
        }
        if (!course.schoolId().equals(user.schoolId().value())) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS,
                    "跨校访问", Map.of("course_id", course.id()));
        }
        if (!assignment.studentAccountId().equals(user.accountId())) {
            throw new DomainException(ErrorCode.FORBIDDEN, "任务不属于当前学生");
        }
    }
}
