package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.domain.Account;
import com.shijiangtai.m1auth.domain.AccountRepository;
import com.shijiangtai.m1auth.domain.AccountRoleRepository;
import com.shijiangtai.m1auth.domain.Major;
import com.shijiangtai.m1auth.domain.OrganizationRepository;
import com.shijiangtai.m1auth.domain.ProfileRepository;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 教师管理：专业范围（FR-M1-08）+ 附加角色（FR-M1-12）。
 *
 * <ul>
 *   <li>仅管理员可改，且只能改本校教师；教师本人不能修改自己的专业或角色
 *   <li>专业：至少保留一个本校启用专业；旧关系置失效并保留历史，不物理删除
 *   <li>角色：只能给本校教师<b>叠加 / 撤销 admin</b>，不能改变主身份，
 *       也不能授予 student（主身份由注册决定，改了会让档案与权限脱节）
 * </ul>
 */
@Service
public class TeacherMajorService {

    private final AccountRepository accounts;
    private final AccountRoleRepository accountRoles;
    private final ProfileRepository profiles;
    private final OrganizationRepository organization;
    private final SchoolApplicationService schools;
    private final Clock clock;

    public TeacherMajorService(
            AccountRepository accounts,
            AccountRoleRepository accountRoles,
            ProfileRepository profiles,
            OrganizationRepository organization,
            SchoolApplicationService schools,
            Clock clock) {
        this.accounts = accounts;
        this.accountRoles = accountRoles;
        this.profiles = profiles;
        this.organization = organization;
        this.schools = schools;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TeacherView> listTeachers(M1Principal principal) {
        SchoolId schoolId = schools.requireBoundSchool(principal);
        return profiles.findTeacherIdsBySchool(schoolId.value()).stream()
                .map(accounts::findById)
                .flatMap(java.util.Optional::stream)
                .map(this::toView)
                .toList();
    }

    @Transactional
    public TeacherView updateMajors(
            M1Principal principal, UUID teacherId, List<UUID> majorIds) {
        SchoolId schoolId = schools.requireBoundSchool(principal);

        AccountId accountId = new AccountId(teacherId);
        Account teacher = accounts.findById(accountId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "账号不存在"));
        if (teacher.type() != AccountType.TEACHER) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "该账号不是教师");
        }
        if (teacher.schoolId() == null || !teacher.schoolId().equals(schoolId)) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "只能修改本校教师");
        }

        if (majorIds == null || majorIds.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "至少保留一个有效专业");
        }
        List<UUID> distinct = majorIds.stream().distinct().toList();
        for (UUID majorId : distinct) {
            Major major = organization.findMajorById(majorId).orElseThrow(
                    () -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "专业不存在"));
            if (!major.schoolId().equals(schoolId)) {
                throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "专业不属于本校");
            }
            if (!major.isActive()) {
                throw new DomainException(ErrorCode.MAJOR_DISABLED, "专业已停用");
            }
        }

        profiles.replaceActiveMajors(accountId, distinct, clock.instant());
        return toView(teacher);
    }

    /**
     * 授予 / 撤销管理员角色（FR-M1-12）。
     *
     * <p>只叠加 {@code admin}，不改主身份：主身份决定档案类型与既有外键，
     * 改它会让教师档案与权限脱节。教师被授予后同时拥有教师与管理权限（并集），
     * 撤销后需重新登录才失去管理端访问。
     */
    @Transactional
    public TeacherView updateAdminRole(M1Principal principal, UUID teacherId, boolean grant) {
        SchoolId schoolId = schools.requireBoundSchool(principal);

        AccountId accountId = new AccountId(teacherId);
        if (principal.accountId().equals(accountId)) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "不能修改自己的角色");
        }

        Account teacher = accounts.findById(accountId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "账号不存在"));
        if (teacher.type() != AccountType.TEACHER) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "只能给本校教师授予管理员角色");
        }
        if (teacher.schoolId() == null || !teacher.schoolId().equals(schoolId)) {
            throw new DomainException(ErrorCode.CROSS_SCHOOL_ACCESS, "只能修改本校教师");
        }

        if (grant) {
            accountRoles.grant(accountId, AccountType.ADMIN, principal.accountId(), clock.instant());
        } else {
            accountRoles.revoke(accountId, AccountType.ADMIN);
        }
        return toView(teacher);
    }

    private TeacherView toView(Account account) {
        List<MajorItem> majors = profiles.findActiveMajorIds(account.id()).stream()
                .map(organization::findMajorById)
                .flatMap(java.util.Optional::stream)
                .map(major -> new MajorItem(major.id(), major.name()))
                .toList();
        List<String> roles = accountRoles.findRoles(account.id()).stream()
                .map(role -> role.name().toLowerCase(java.util.Locale.ROOT))
                .sorted()
                .toList();
        return new TeacherView(
                account.id().value(), account.username(),
                account.schoolId() == null ? null : account.schoolId().value(),
                majors, roles);
    }

    public record TeacherView(
            UUID accountId, String username, UUID schoolId,
            List<MajorItem> majors, List<String> roles) {
    }

    public record MajorItem(UUID id, String name) {
    }
}
