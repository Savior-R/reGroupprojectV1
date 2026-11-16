package com.shijiangtai.m2course.api;

import com.shijiangtai.m2course.api.dto.AddStudentByAccountRequest;
import com.shijiangtai.m2course.api.dto.AddStudentByClassRequest;
import com.shijiangtai.m2course.api.dto.AddTeacherRequest;
import com.shijiangtai.m2course.api.dto.PageResponse;
import com.shijiangtai.m2course.application.CourseMembershipService;
import com.shijiangtai.m2course.application.command.MemberQuery;
import com.shijiangtai.m2course.application.view.BatchAddStudentsResult;
import com.shijiangtai.m2course.application.view.CourseStudentView;
import com.shijiangtai.m2course.application.view.CourseTeacherView;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.ClassId;
import com.shijiangtai.shared.domain.CourseId;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** M2 课程成员接口：老师组、学生名单、批量 / 单个加人、学生自主加入与退出。 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}")
public class CourseMemberController {
    private final CourseMembershipService courseMembershipService;
    private final CurrentUserProvider currentUserProvider;

    public CourseMemberController(
            CourseMembershipService courseMembershipService,
            CurrentUserProvider currentUserProvider) {
        this.courseMembershipService = courseMembershipService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/teachers")
    public List<CourseTeacherView> listTeachers(
            @PathVariable UUID courseId,
            @RequestParam(name = "include_history", defaultValue = "false") boolean includeHistory) {
        return courseMembershipService.listTeachers(
                currentUserProvider.require(), new CourseId(courseId), includeHistory);
    }

    @PostMapping("/teachers")
    @ResponseStatus(HttpStatus.CREATED)
    public void addTeacher(
            @PathVariable UUID courseId, @RequestBody @Valid AddTeacherRequest request) {
        courseMembershipService.addTeacher(
                currentUserProvider.require(),
                new CourseId(courseId),
                new AccountId(request.accountId()));
    }

    @DeleteMapping("/teachers/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTeacher(@PathVariable UUID courseId, @PathVariable UUID accountId) {
        courseMembershipService.removeTeacher(
                currentUserProvider.require(), new CourseId(courseId), new AccountId(accountId));
    }

    @GetMapping("/students")
    public PageResponse<CourseStudentView> listStudents(
            @PathVariable UUID courseId,
            @RequestParam(name = "include_history", defaultValue = "false") boolean includeHistory,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(courseMembershipService.listStudents(
                currentUserProvider.require(),
                new CourseId(courseId),
                new MemberQuery(includeHistory, page, size)));
    }

    @PostMapping("/students/class")
    public BatchAddStudentsResult addStudentsByClass(
            @PathVariable UUID courseId, @RequestBody @Valid AddStudentByClassRequest request) {
        return courseMembershipService.addStudentsByClass(
                currentUserProvider.require(), new CourseId(courseId), new ClassId(request.classId()));
    }

    @PostMapping("/students/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addStudentByAccount(
            @PathVariable UUID courseId, @RequestBody @Valid AddStudentByAccountRequest request) {
        courseMembershipService.addStudentByAccount(
                currentUserProvider.require(),
                new CourseId(courseId),
                new AccountId(request.studentAccountId()));
    }

    @DeleteMapping("/students/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeStudent(@PathVariable UUID courseId, @PathVariable UUID accountId) {
        courseMembershipService.removeStudent(
                currentUserProvider.require(), new CourseId(courseId), new AccountId(accountId));
    }

    @PostMapping("/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void join(@PathVariable UUID courseId) {
        courseMembershipService.join(currentUserProvider.require(), new CourseId(courseId));
    }

    @PostMapping("/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable UUID courseId) {
        courseMembershipService.leave(currentUserProvider.require(), new CourseId(courseId));
    }
}
