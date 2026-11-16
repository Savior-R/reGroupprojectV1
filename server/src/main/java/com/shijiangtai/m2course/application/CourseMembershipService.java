package com.shijiangtai.m2course.application;

import com.shijiangtai.m2course.application.command.MemberQuery;
import com.shijiangtai.m2course.application.view.BatchAddStudentsResult;
import com.shijiangtai.m2course.application.view.CourseStudentView;
import com.shijiangtai.m2course.application.view.CourseTeacherView;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.CourseId;
import com.shijiangtai.shared.domain.PageResult;
import java.util.List;

/** 课程成员用例：老师组、学生名单、按班级 / 账号加人、学生自主加入与退出。 */
public interface CourseMembershipService {

    List<CourseTeacherView> listTeachers(CurrentUser user, CourseId courseId, boolean includeHistory);

    void addTeacher(CurrentUser user, CourseId courseId, AccountId teacherId);

    void removeTeacher(CurrentUser user, CourseId courseId, AccountId teacherId);

    PageResult<CourseStudentView> listStudents(CurrentUser user, CourseId courseId, MemberQuery query);

    BatchAddStudentsResult addStudentsByClass(CurrentUser user, CourseId courseId, ClassId classId);

    void addStudentByAccount(CurrentUser user, CourseId courseId, AccountId studentId);

    void removeStudent(CurrentUser user, CourseId courseId, AccountId studentId);

    void join(CurrentUser user, CourseId courseId);

    void leave(CurrentUser user, CourseId courseId);
}
