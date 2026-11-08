package com.shijiangtai.m1auth.api;

import com.shijiangtai.m1auth.api.dto.AdminRequests.UpdateTeacherMajorsRequest;
import com.shijiangtai.m1auth.application.M1PrincipalProvider;
import com.shijiangtai.m1auth.application.TeacherMajorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本校教师与其专业范围（FR-M1-08）。教师本人不可修改自己的专业。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminTeacherController {

    private final TeacherMajorService teachers;
    private final M1PrincipalProvider principals;

    public AdminTeacherController(
            TeacherMajorService teachers, M1PrincipalProvider principals) {
        this.teachers = teachers;
        this.principals = principals;
    }

    @GetMapping("/teachers")
    public List<TeacherMajorService.TeacherView> list() {
        return teachers.listTeachers(principals.require());
    }

    @PutMapping("/teachers/{accountId}/majors")
    public TeacherMajorService.TeacherView updateMajors(
            @PathVariable UUID accountId,
            @Valid @RequestBody UpdateTeacherMajorsRequest request) {
        return teachers.updateMajors(principals.require(), accountId, request.majorIds());
    }
}
