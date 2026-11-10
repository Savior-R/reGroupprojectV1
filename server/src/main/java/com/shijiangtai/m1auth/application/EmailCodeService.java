package com.shijiangtai.m1auth.application;

import com.shijiangtai.m1auth.application.port.MailSender;
import com.shijiangtai.m1auth.application.port.PasswordHasher;
import com.shijiangtai.m1auth.domain.Account;
import com.shijiangtai.m1auth.domain.AccountRepository;
import com.shijiangtai.m1auth.domain.AuthLoginEvent;
import com.shijiangtai.m1auth.domain.AuthMethodRepository;
import com.shijiangtai.m1auth.domain.EmailAddress;
import com.shijiangtai.m1auth.domain.EmailLoginCode;
import com.shijiangtai.m1auth.domain.VerificationCodeHasher;
import com.shijiangtai.m1auth.infrastructure.config.M1Properties;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邮箱验证码：发送 / 登录 / 绑定 / 换绑 / 解绑（FR-M1-09、FR-M1-11）。
 *
 * <p>安全要点：
 * <ul>
 *   <li>服务端只存 HMAC-SHA256，明文仅存在于发送请求内存
 *   <li>6 位、5 分钟、单次使用、5 次尝试、60 秒重发、邮箱 5 次/小时、IP 20 次/小时
 *   <li>所有失败统一 {@code UNAUTHORIZED}，不泄露邮箱 / 账号是否存在
 * </ul>
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SUBJECT = "【试讲台】邮箱验证码";

    private final AccountRepository accounts;
    private final AuthMethodRepository authMethods;
    private final MailSender mailSender;
    private final VerificationCodeHasher hasher;
    private final PasswordHasher passwordHasher;
    private final AuthApplicationService authService;
    private final LoginAuditWriter auditWriter;
    private final M1Properties properties;
    private final Clock clock;

    public EmailCodeService(
            AccountRepository accounts,
            AuthMethodRepository authMethods,
            MailSender mailSender,
            VerificationCodeHasher hasher,
            PasswordHasher passwordHasher,
            AuthApplicationService authService,
            LoginAuditWriter auditWriter,
            M1Properties properties,
            Clock clock) {
        this.accounts = accounts;
        this.authMethods = authMethods;
        this.mailSender = mailSender;
        this.hasher = hasher;
        this.passwordHasher = passwordHasher;
        this.authService = authService;
        this.auditWriter = auditWriter;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------- 发送验证码

    /** 登录用验证码；统一 202，不提示邮箱是否存在（FR-M1-09 流程 6.5）。 */
    @Transactional
    public void sendLoginCode(String rawEmail, String requestIp) {
        sendCode(EmailAddress.of(rawEmail), EmailLoginCode.Purpose.LOGIN, requestIp);
    }

    /** 绑定 / 换绑用验证码（需登录，由 Controller 保证）。 */
    @Transactional
    public void sendBindCode(String rawEmail, String requestIp) {
        sendCode(EmailAddress.of(rawEmail), EmailLoginCode.Purpose.BIND, requestIp);
    }

    private void sendCode(EmailAddress email, EmailLoginCode.Purpose purpose, String requestIp) {
        Instant now = clock.instant();
        enforceRateLimits(email.value(), requestIp, now);

        String code = generateCode();
        authMethods.invalidatePendingCodes(email.value(), now);
        authMethods.insertCode(new EmailLoginCode(
                UUID.randomUUID(),
                email.value(),
                hasher.hash(email.value(), code),
                purpose,
                now.plus(properties.emailCode().ttl()),
                0,
                null,
                requestIp,
                now));

        try {
            mailSender.send(email.value(), SUBJECT, body(code));
        } catch (MailSender.MailSendException e) {
            // 邮件失败不改变账号状态，也不回滚已写入的验证码（重发即可）
            log.warn("验证码邮件发送失败 email={}", email.value());
            throw new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "邮件服务暂不可用，请稍后重试");
        }
    }

    private void enforceRateLimits(String email, String ip, Instant now) {
        M1Properties.EmailCode config = properties.emailCode();

        Optional<Instant> lastSent = authMethods.findLastCodeCreatedAt(email);
        if (lastSent.isPresent()
                && lastSent.get().plus(config.resendInterval()).isAfter(now)) {
            throw new DomainException(ErrorCode.RATE_LIMITED, "发送过于频繁，请稍后再试");
        }

        if (authMethods.countCodesByEmailSince(email, now.minus(Duration.ofHours(1)))
                >= config.emailHourlyLimit()) {
            throw new DomainException(ErrorCode.RATE_LIMITED, "该邮箱今日发送次数过多，请稍后再试");
        }

        if (ip != null && !ip.isBlank()
                && authMethods.countCodesByIpSince(ip, now.minus(Duration.ofHours(1)))
                >= config.ipHourlyLimit()) {
            throw new DomainException(ErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试");
        }
    }

    // ------------------------------------------------------------- 验证码登录

    @Transactional
    public AuthApplicationService.AuthResult loginWithEmail(
            String rawEmail, String code, String requestIp, String userAgent) {
        String email = EmailAddress.of(rawEmail).value();
        Instant now = clock.instant();

        Optional<Account> found = accounts.findByEmail(new EmailAddress(email))
                .filter(Account::canLoginByEmail);

        if (found.isEmpty()) {
            auditFailure(email, requestIp, userAgent, now);
            throw unauthorized();
        }

        EmailLoginCode stored = authMethods
                .findLatestCode(email, EmailLoginCode.Purpose.LOGIN)
                .orElseThrow(() -> {
                    auditFailure(email, requestIp, userAgent, now);
                    return unauthorized();
                });

        if (!stored.isUsable(now, properties.emailCode().maxAttempts())) {
            auditFailure(email, requestIp, userAgent, now);
            throw unauthorized();
        }

        if (!stored.matchesHash(hasher.hash(email, code))) {
            authMethods.updateCode(stored.withFailedAttempt());
            auditFailure(email, requestIp, userAgent, now);
            throw unauthorized();
        }

        authMethods.updateCode(stored.consume(now));
        Account account = found.get();
        auditWriter.write(new AuthLoginEvent(
                UUID.randomUUID(), account.id(), AuthLoginEvent.LoginMethod.EMAIL, null,
                true, requestIp, userAgent, now));
        return authService.issue(account);
    }

    // --------------------------------------------------------- 绑定 / 解绑

    /** 绑定 / 换绑邮箱：需当前密码 + 验证码；邮箱平台唯一（FR-M1-09）。 */
    @Transactional
    public void bindEmail(
            M1Principal principal, String rawEmail, String code, String currentPassword) {
        Account account = requireAccount(principal);
        if (!passwordHasher.matches(currentPassword, account.passwordHash())) {
            throw unauthorized();
        }

        EmailAddress email = EmailAddress.of(rawEmail);
        Instant now = clock.instant();

        EmailLoginCode stored = authMethods
                .findLatestCode(email.value(), EmailLoginCode.Purpose.BIND)
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "验证码无效或已过期"));

        if (!stored.isUsable(now, properties.emailCode().maxAttempts())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "验证码无效或已过期");
        }
        if (!stored.matchesHash(hasher.hash(email.value(), code))) {
            authMethods.updateCode(stored.withFailedAttempt());
            throw new DomainException(ErrorCode.UNAUTHORIZED, "验证码无效或已过期");
        }

        // 一个邮箱只能绑定一个账号；文案统一，不泄露已绑定账号
        accounts.findByEmail(email)
                .filter(other -> !other.id().equals(account.id()))
                .ifPresent(other -> {
                    throw new DomainException(ErrorCode.EMAIL_TAKEN, "该邮箱已被使用");
                });

        authMethods.updateCode(stored.consume(now));
        accounts.save(account.bindVerifiedEmail(email, now));
    }

    /** 解绑邮箱：需当前密码；解绑后不能再用验证码登录。 */
    @Transactional
    public void unbindEmail(M1Principal principal, String currentPassword) {
        Account account = requireAccount(principal);
        if (!passwordHasher.matches(currentPassword, account.passwordHash())) {
            throw unauthorized();
        }
        if (!account.canLoginByEmail()) {
            throw new DomainException(ErrorCode.VALIDATION_ERROR, "尚未绑定邮箱");
        }
        // 密码恒存在，解绑邮箱不会使账号失去所有登录方式；如未来支持清空密码，
        // 此处需改为「至少保留一种登录方式」校验（FR-M1-11）。
        accounts.save(account.unbindEmail(clock.instant()));
    }

    // ------------------------------------------------------------------ 内部

    private Account requireAccount(M1Principal principal) {
        return accounts.findById(principal.accountId())
                .orElseThrow(() -> new DomainException(ErrorCode.UNAUTHORIZED, "账号不存在"));
    }

    private void auditFailure(String email, String ip, String userAgent, Instant now) {
        // 邮箱可能未绑定任何账号，故 account_id 为空；不记录验证码
        auditWriter.write(AuthLoginEvent.failure(
                UUID.randomUUID(), AuthLoginEvent.LoginMethod.EMAIL, null, ip, userAgent, now));
    }

    private static DomainException unauthorized() {
        return new DomainException(ErrorCode.UNAUTHORIZED, "邮箱或验证码错误");
    }

    private static String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private static String body(String code) {
        return """
                您的验证码是 %s，5 分钟内有效。
                如非本人操作请忽略本邮件，切勿将验证码告知他人。
                """.formatted(code);
    }
}
