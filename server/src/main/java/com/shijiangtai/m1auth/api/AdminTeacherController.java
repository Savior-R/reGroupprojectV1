package com.shijiangtai.m1auth.api;

import com.shijiangtai.m1auth.api.dto.AdminRequests.UpdateTeacherMajorsRequest;
import com.shijiangtai.m1auth.api.dto.AdminRequests.UpdateTeacherRolesRequest;
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

/**
 * 本校教师管理：专业范围（FR-M1-08）+ 附加角色（FR-M1-12）。
 * 教师本人不能修改自己的专业或角色。
 */
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

    /** 授予 / 撤销管理员角色；权限码 {@code admin:roles}，仅管理员可用。 */
    @PutMapping("/teachers/{accountId}/roles")
    public TeacherMajorService.TeacherView updateRoles(
            @PathVariable UUID accountId,
            @Valid @RequestBody UpdateTeacherRolesRequest request) {
        return teachers.updateAdminRole(principals.require(), accountId, request.admin());
    }
}
