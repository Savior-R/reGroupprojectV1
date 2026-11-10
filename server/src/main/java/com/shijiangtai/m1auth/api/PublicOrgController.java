package com.shijiangtai.m1auth.api;

import com.shijiangtai.m1auth.application.OrgApplicationService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册选项（公开，FR-M1-07「公开接口」）。
 * 只返回启用中的专业 / 班级；停用的不出现在选项中（03 §3.5）。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicOrgController {

    private final OrgApplicationService org;

    public PublicOrgController(OrgApplicationService org) {
        this.org = org;
    }

    @GetMapping("/schools")
    public List<OrgApplicationService.SchoolView> schools() {
        return org.listSchools();
    }

    @GetMapping("/schools/{schoolId}/majors")
    public List<OrgApplicationService.MajorView> majors(@PathVariable UUID schoolId) {
        return org.publicMajors(schoolId);
    }

    @GetMapping("/schools/{schoolId}/grades")
    public List<String> grades(@PathVariable UUID schoolId) {
        return org.publicGrades(schoolId);
    }

    @GetMapping("/classes")
    public List<OrgApplicationService.ClassView> classes(
            @RequestParam UUID schoolId,
            @RequestParam(required = false) UUID majorId,
            @RequestParam(required = false) String grade) {
        return org.publicClasses(schoolId, majorId, grade);
    }
}
