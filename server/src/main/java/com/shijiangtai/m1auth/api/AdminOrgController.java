package com.shijiangtai.m1auth.api;

import com.shijiangtai.m1auth.api.dto.AdminRequests.CreateClassRequest;
import com.shijiangtai.m1auth.api.dto.AdminRequests.CreateMajorRequest;
import com.shijiangtai.m1auth.api.dto.AdminRequests.UpdateClassRequest;
import com.shijiangtai.m1auth.api.dto.AdminRequests.UpdateMajorRequest;
import com.shijiangtai.m1auth.application.M1PrincipalProvider;
import com.shijiangtai.m1auth.application.OrgApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 专业 / 班级维护（FR-M1-04、FR-M1-05）。导入另见 {@link AdminImportController}。 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOrgController {

    private final OrgApplicationService org;
    private final M1PrincipalProvider principals;

    public AdminOrgController(OrgApplicationService org, M1PrincipalProvider principals) {
        this.org = org;
        this.principals = principals;
    }

    @GetMapping("/majors")
    public List<OrgApplicationService.MajorView> listMajors() {
        return org.listMajors(principals.require());
    }

    @PostMapping("/majors")
    @ResponseStatus(HttpStatus.CREATED)
    public OrgApplicationService.MajorView createMajor(
            @Valid @RequestBody CreateMajorRequest request) {
        return org.createMajor(principals.require(), request.name());
    }

    @PatchMapping("/majors/{majorId}")
    public OrgApplicationService.MajorView updateMajor(
            @PathVariable UUID majorId, @RequestBody UpdateMajorRequest request) {
        return org.updateMajor(principals.require(), majorId, request.name(), request.active());
    }

    @GetMapping("/classes")
    public List<OrgApplicationService.ClassView> listClasses(
            @RequestParam(required = false) UUID majorId,
            @RequestParam(required = false) String grade) {
        return org.listClasses(principals.require(), majorId, grade);
    }

    @PostMapping("/classes")
    @ResponseStatus(HttpStatus.CREATED)
    public OrgApplicationService.ClassView createClass(
            @Valid @RequestBody CreateClassRequest request) {
        return org.createClass(
                principals.require(), request.majorId(), request.grade(), request.name());
    }

    @PatchMapping("/classes/{classId}")
    public OrgApplicationService.ClassView updateClass(
            @PathVariable UUID classId, @RequestBody UpdateClassRequest request) {
        return org.updateClass(
                principals.require(),
                classId,
                request.majorId(),
                request.grade(),
                request.name(),
                request.active());
    }
}
