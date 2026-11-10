package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.domain.OrganizationRepository;
import com.shijiangtai.m1auth.domain.School;
import com.shijiangtai.m1auth.domain.SchoolAdminRepository;
import com.shijiangtai.m1auth.infrastructure.security.JwtTokenIssuer;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 建校与本校信息维护（FR-M1-03、FR-M1-04）。
 *
 * <p>管理员与学校一对一：建校后 {@code school_admins} 唯一索引兜底，
 * 响应返回新 token（{@code school_id} 已写入），不要求重新登录（03 §3.4）。
 */
@Service
public class SchoolApplicationService {

    private static final Logger log = LoggerFactory.getLogger(SchoolApplicationService.class);

    private final OrganizationRepository organization;
    private final SchoolAdminRepository schoolAdmins;
    private final JwtTokenIssuer tokens;
    private final Clock clock;

    public SchoolApplicationService(
            OrganizationRepository organization,
            SchoolAdminRepository schoolAdmins,
            JwtTokenIssuer tokens,
            Clock clock) {
        this.organization = organization;
        this.schoolAdmins = schoolAdmins;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public SchoolCreatedResult createSchool(M1Principal principal, String name) {
        requireAdmin(principal);
        if (schoolAdmins.findActiveSchoolIdByAccount(principal.accountId()).isPresent()) {
            throw new DomainException(ErrorCode.SCHOOL_ALREADY_BOUND, "已绑定学校，不能重复创建");
        }

        Instant now = clock.instant();
        School school = School.create(new SchoolId(UUID.randomUUID()), name, now);
        if (organization.findSchoolByName(school.name()).isPresent()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "学校名称已存在");
        }

        organization.saveSchool(school);
        schoolAdmins.insert(school.id(), principal.accountId(), true, now);

        JwtTokenIssuer.IssuedToken issued =
                tokens.issue(principal.accountId(), AccountType.ADMIN, school.id());
        log.info("学校创建成功 school_id={} account_id={}",
                school.id().value(), principal.accountId().value());
        return new SchoolCreatedResult(
                new SchoolView(school.id().value(), school.name()),
                issued.token(),
                issued.expiresAt());
    }

    @Transactional(readOnly = true)
    public SchoolView currentSchool(M1Principal principal) {
        SchoolId schoolId = principal.schoolId();
        if (schoolId == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "尚未创建学校");
        }
        School school = organization.findSchoolById(schoolId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "学校不存在"));
        return new SchoolView(school.id().value(), school.name());
    }

    @Transactional
    public SchoolView updateSchool(M1Principal principal, String name) {
        SchoolId schoolId = requireBoundSchool(principal);
        School school = organization.findSchoolById(schoolId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "学校不存在"));

        String newName = School.create(schoolId, name, clock.instant()).name();
        organization.findSchoolByName(newName)
                .filter(other -> !other.id().equals(schoolId))
                .ifPresent(other -> {
                    throw new DomainException(ErrorCode.VALIDATION_ERROR, "学校名称已存在");
                });
        return toView(organization.saveSchool(school.rename(newName, clock.instant())));
    }

    /** 管理员接口的统一前置校验：必须是管理员且已建校。 */
    SchoolId requireBoundSchool(M1Principal principal) {
        requireAdmin(principal);
        if (principal.schoolId() == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "尚未创建学校");
        }
        return principal.schoolId();
    }

    static void requireAdmin(M1Principal principal) {
        if (principal.type() != AccountType.ADMIN) {
            throw new DomainException(ErrorCode.ROLE_NOT_ALLOWED, "仅管理员可执行该操作");
        }
    }

    private static SchoolView toView(School school) {
        return new SchoolView(school.id().value(), school.name());
    }

    public record SchoolView(UUID schoolId, String name) {
    }

    public record SchoolCreatedResult(SchoolView school, String token, Instant expiresAt) {
    }
}
