package com.shijiangtai.m1auth.api;

import com.shijiangtai.m1auth.api.dto.AdminRequests.CreateSchoolRequest;
import com.shijiangtai.m1auth.api.dto.AdminRequests.UpdateSchoolRequest;
import com.shijiangtai.m1auth.application.M1PrincipalProvider;
import com.shijiangtai.m1auth.application.SchoolApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 建校与本校信息（FR-M1-03、FR-M1-04）。
 *
 * <p>未绑定管理员只允许 {@code POST /admin/schools}；其余管理接口由 service 校验
 * {@code school_id} 非空（03 §3.4）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminSchoolController {

    private final SchoolApplicationService schools;
    private final M1PrincipalProvider principals;

    public AdminSchoolController(
            SchoolApplicationService schools, M1PrincipalProvider principals) {
        this.schools = schools;
        this.principals = principals;
    }

    /** 建校成功后返回新 token，客户端替换旧 token，不要求重新登录。 */
    @PostMapping("/schools")
    @ResponseStatus(HttpStatus.CREATED)
    public SchoolApplicationService.SchoolCreatedResult create(
            @Valid @RequestBody CreateSchoolRequest request) {
        return schools.createSchool(principals.require(), request.name());
    }

    @GetMapping("/school")
    public SchoolApplicationService.SchoolView current() {
        return schools.currentSchool(principals.require());
    }

    @PatchMapping("/school")
    public SchoolApplicationService.SchoolView update(
            @Valid @RequestBody UpdateSchoolRequest request) {
        return schools.updateSchool(principals.require(), request.name());
    }
}
