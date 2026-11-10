package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.application.port.PasswordHasher;
import com.shijiangtai.m1auth.domain.Account;
import com.shijiangtai.m1auth.domain.AccountRepository;
import com.shijiangtai.m1auth.domain.AuthLoginEvent;
import com.shijiangtai.m1auth.domain.AuthMethodRepository;
import com.shijiangtai.m1auth.domain.EmailAddress;
import com.shijiangtai.m1auth.domain.Major;
import com.shijiangtai.m1auth.domain.OrganizationRepository;
import com.shijiangtai.m1auth.domain.PasswordPolicy;
import com.shijiangtai.m1auth.domain.ProfileRepository;
import com.shijiangtai.m1auth.domain.School;
import com.shijiangtai.m1auth.domain.SchoolAdminRepository;
import com.shijiangtai.m1auth.domain.SchoolClass;
import com.shijiangtai.m1auth.domain.UsernamePolicy;
import com.shijiangtai.m1auth.infrastructure.security.JwtTokenIssuer;
import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册 / 密码登录 / 当前账号信息（FR-M1-01、FR-M1-02、M1 §3.8）。
 *
 * <p>三种登录方式最终都走 {@link #issue(Account)}，保证 JWT claims 完全一致（M1-T14）。
 */
@Service
public class AuthApplicationService {

    private static final Logger log = LoggerFactory.getLogger(AuthApplicationService.class);

    private static final String LOGIN_FAILED = "用户名或密码错误";

    private final AccountRepository accounts;
    private final ProfileRepository profiles;
    private final OrganizationRepository organization;
    private final SchoolAdminRepository schoolAdmins;
    private final LoginAuditWriter auditWriter;
    private final PasswordHasher passwordHasher;
    private final JwtTokenIssuer tokens;
    private final Clock clock;

    public AuthApplicationService(
            AccountRepository accounts,
            ProfileRepository profiles,
            OrganizationRepository organization,
            SchoolAdminRepository schoolAdmins,
            LoginAuditWriter auditWriter,
            PasswordHasher passwordHasher,
            JwtTokenIssuer tokens,
            Clock clock) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.organization = organization;
        this.schoolAdmins = schoolAdmins;
        this.auditWriter = auditWriter;
        this.passwordHasher = passwordHasher;
        this.tokens = tokens;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 注册

    @Transactional
    public AuthResult register(RegisterCommand command, String ip, String userAgent) {
        AccountType type = parseAccountType(command.accountType());
        UsernamePolicy.validate(command.username());
        PasswordPolicy.validate(command.password());
        String username = UsernamePolicy.normalize(command.username());

        if (accounts.existsByUsername(username)) {
            throw new DomainException(ErrorCode.USERNAME_TAKEN, "用户名已被占用，请在姓名后加区分后缀");
        }

        EmailAddress email = parseEmail(command.email());

        Instant now = clock.instant();
        SchoolId schoolId = null;
        UUID classId = null;
        List<UUID> majorIds = List.of();

        switch (type) {
            case STUDENT -> {
                schoolId = requireSchool(command.schoolId());
                classId = requireClass(schoolId, command.classId());
            }
            case TEACHER -> {
                schoolId = requireSchool(command.schoolId());
                majorIds = requireMajors(schoolId, command.majorIds());
            }
            case ADMIN -> {
                // 管理员注册不绑定学校，登录后走建校流程（FR-M1-03）
            }
        }

        Account account = Account.register(
                new AccountId(UUID.randomUUID()),
                username,
                passwordHasher.hash(command.password()),
                email,
                null,
                type,
                schoolId,
                now);
        accounts.save(account);

        switch (type) {
            case STUDENT -> profiles.insertStudentProfile(account.id(), classId);
            case TEACHER -> {
                profiles.insertTeacherProfile(account.id());
                profiles.replaceActiveMajors(account.id(), majorIds, now);
            }
            case ADMIN -> {
                // 无档案，school_id 保持空
            }
        }

        log.info("账号注册成功 account_id={} type={}", account.id().value(), type);
        return issue(account);
    }

    // ------------------------------------------------------------------ 登录

    @Transactional
    public AuthResult login(LoginCommand command, String ip, String userAgent) {
        String username = UsernamePolicy.normalize(command.username());
        Optional<Account> found = username == null
                ? Optional.empty() : accounts.findByUsername(username);

        if (found.isEmpty()) {
            audit(AuthLoginEvent.failure(
                    UUID.randomUUID(), AuthLoginEvent.LoginMethod.PASSWORD, null, ip, userAgent,
                    clock.instant()));
            throw new DomainException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED);
        }

        Account account = found.get();
        if (!passwordHasher.matches(command.password(), account.passwordHash())) {
            audit(AuthLoginEvent.failure(
                    UUID.randomUUID(), AuthLoginEvent.LoginMethod.PASSWORD, null, ip, userAgent,
                    clock.instant()));
            throw new DomainException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED);
        }

        audit(new AuthLoginEvent(UUID.randomUUID(), account.id(),
                AuthLoginEvent.LoginMethod.PASSWORD, null, true, ip, userAgent, clock.instant()));
        return issue(account);
    }

    // --------------------------------------------------------------------- me

    @Transactional(readOnly = true)
    public AccountView me(M1Principal principal) {
        Account account = accounts.findById(principal.accountId())
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "账号不存在"));
        return view(account);
    }

    /** 供其他服务复用：账号 + 角色档案视图。 */
    @Transactional(readOnly = true)
    public AccountView view(Account account) {
        SchoolId effectiveSchool = effectiveSchool(account);
        String schoolName = effectiveSchool == null ? null
                : organization.findSchoolById(effectiveSchool).map(School::name).orElse(null);

        StudentView student = account.type() == AccountType.STUDENT
                ? studentView(account.id()) : null;
        TeacherView teacher = account.type() == AccountType.TEACHER
                ? teacherView(account.id()) : null;

        return new AccountView(
                account.id().value(),
                account.username(),
                account.type().name().toLowerCase(Locale.ROOT),
                effectiveSchool == null ? null : effectiveSchool.value(),
                schoolName,
                account.email() == null ? null : account.email().value(),
                account.canLoginByEmail(),
                student,
                teacher);
    }

    // ------------------------------------------------------------------ 内部

    /** 签发 JWT：三种登录方式共用，claims 一致（M1-T14）。 */
    AuthResult issue(Account account) {
        SchoolId effectiveSchool = effectiveSchool(account);
        JwtTokenIssuer.IssuedToken issued =
                tokens.issue(account.id(), account.type(), effectiveSchool);
        return new AuthResult(issued.token(), issued.expiresAt(), view(account));
    }

    /** 管理员学校归属以 school_admins 为准；其他人取 accounts.school_id（03 §3.4）。 */
    private SchoolId effectiveSchool(Account account) {
        if (account.type() == AccountType.ADMIN) {
            return schoolAdmins.findActiveSchoolIdByAccount(account.id()).orElse(null);
        }
        return account.schoolId();
    }

    private StudentView studentView(AccountId accountId) {
        return profiles.findStudentClassId(accountId)
                .flatMap(organization::findClassById)
                .map(schoolClass -> new StudentView(
                        schoolClass.id(),
                        schoolClass.grade(),
                        organization.findMajorById(schoolClass.majorId())
                                .map(Major::name).orElse(null),
                        schoolClass.name()))
                .orElse(null);
    }

    private TeacherView teacherView(AccountId accountId) {
        List<MajorView> majors = profiles.findActiveMajorIds(accountId).stream()
                .map(organization::findMajorById)
                .flatMap(Optional::stream)
                .map(major -> new MajorView(major.id(), major.name()))
                .toList();
        return new TeacherView(majors);
    }

    private static AccountType parseAccountType(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "账号类型必填");
        }
        try {
            return AccountType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "账号类型非法");
        }
    }

    private EmailAddress parseEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return null;
        }
        EmailAddress email = EmailAddress.of(rawEmail);
        if (accounts.existsByEmail(email)) {
            throw new DomainException(ErrorCode.EMAIL_TAKEN, "该邮箱已绑定其他账号");
        }
        return email;
    }

    private SchoolId requireSchool(UUID schoolId) {
        if (schoolId == null) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "学校必填");
        }
        SchoolId id = new SchoolId(schoolId);
        organization.findSchoolById(id).orElseThrow(
                () -> new DomainException(ErrorCode.VALIDATION_ERROR, "所选学校不存在"));
        return id;
    }

    private UUID requireClass(SchoolId schoolId, UUID classId) {
        if (classId == null) {
            throw new DomainException(ErrorCode.CLASS_NOT_MATCHED, "请选择班级");
        }
        SchoolClass schoolClass = organization.findClassById(classId)
                .orElseThrow(() -> new DomainException(ErrorCode.CLASS_NOT_MATCHED, "班级不存在"));
        if (!schoolClass.schoolId().equals(schoolId)) {
            throw new DomainException(ErrorCode.CLASS_NOT_MATCHED, "班级不属于所选学校");
        }
        if (!schoolClass.isActive()) {
            throw new DomainException(ErrorCode.CLASS_DISABLED, "班级已停用");
        }
        return classId;
    }

    private List<UUID> requireMajors(SchoolId schoolId, List<UUID> majorIds) {
        if (majorIds == null || majorIds.isEmpty()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "教师至少选择一个专业");
        }
        List<UUID> distinct = majorIds.stream().distinct().toList();
        for (UUID majorId : distinct) {
            Major major = organization.findMajorById(majorId).orElseThrow(
                    () -> new DomainException(ErrorCode.MAJOR_DISABLED, "专业不存在"));
            if (!major.schoolId().equals(schoolId)) {
                throw new DomainException(ErrorCode.VALIDATION_ERROR, "专业不属于所选学校");
            }
            if (!major.isActive()) {
                throw new DomainException(ErrorCode.MAJOR_DISABLED, "专业已停用");
            }
        }
        return distinct;
    }

    private void audit(AuthLoginEvent event) {
        auditWriter.write(event);
    }

    // ---------------------------------------------------------------- records

    /** 注册命令；{@code major_ids} 仅教师使用，{@code class_id} 仅学生使用。 */
    public record RegisterCommand(
            String accountType,
            String username,
            String password,
            String email,
            UUID schoolId,
            UUID classId,
            List<UUID> majorIds) {
    }

    public record LoginCommand(String username, String password) {
    }

    public record AuthResult(String token, Instant expiresAt, AccountView me) {
    }

    public record AccountView(
            UUID accountId,
            String username,
            String accountType,
            UUID schoolId,
            String schoolName,
            String email,
            boolean emailVerified,
            StudentView student,
            TeacherView teacher) {
    }

    public record StudentView(UUID classId, String grade, String majorName, String className) {
    }

    public record TeacherView(List<MajorView> majors) {
    }

    public record MajorView(UUID id, String name) {
    }
}
