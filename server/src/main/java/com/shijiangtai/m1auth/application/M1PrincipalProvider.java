package com.shijiangtai.m1auth.application;

import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import com.shijiangtai.shared.infrastructure.security.HeaderIdentity;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 从 Spring Security 上下文解析 M1 当前用户（JWT {@code sub / account_type / school_id}）。
 * 管理员未建校时 {@code school_id} 缺失，解析为 {@code null}。
 */
@Component
public class M1PrincipalProvider {

    public M1Principal require() {
        return current().orElseThrow(
                () -> new DomainException(ErrorCode.UNAUTHORIZED, "未认证"));
    }

    public java.util.Optional<M1Principal> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return java.util.Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return java.util.Optional.of(fromJwt(jwt));
        }
        // test profile 的头身份兜底（TestHeaderAuthFilter），生产不会出现
        if (principal instanceof HeaderIdentity header) {
            return java.util.Optional.of(new M1Principal(
                    new AccountId(header.accountId()),
                    AccountType.valueOf(header.accountType()),
                    header.schoolId() == null ? null : new SchoolId(header.schoolId())));
        }
        return java.util.Optional.empty();
    }

    public M1Principal fromJwt(Jwt jwt) {
        try {
            AccountId accountId = new AccountId(UUID.fromString(jwt.getSubject()));
            String typeValue = jwt.getClaimAsString("account_type");
            AccountType type = AccountType.valueOf(typeValue.toUpperCase(Locale.ROOT));
            String schoolValue = jwt.getClaimAsString("school_id");
            SchoolId schoolId = schoolValue == null || schoolValue.isBlank()
                    ? null : new SchoolId(UUID.fromString(schoolValue));

            List<String> roleValues = jwt.getClaimAsStringList("roles");
            Set<AccountType> roles = roleValues == null || roleValues.isEmpty()
                    ? Set.of(type)
                    : roleValues.stream()
                            .filter(value -> value != null && !value.isBlank())
                            .map(value -> AccountType.valueOf(value.toUpperCase(Locale.ROOT)))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new M1Principal(accountId, type, schoolId, roles);
        } catch (RuntimeException e) {
            throw new DomainException(ErrorCode.UNAUTHORIZED, "token 内容非法");
        }
    }
}
