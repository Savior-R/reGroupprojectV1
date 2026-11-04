package com.shijiangtai.m3classroom.api.security;

import com.shijiangtai.shared.application.AccountType;
import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.AccountId;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import com.shijiangtai.shared.domain.SchoolId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    @Override
    public CurrentUser require() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw unauthorized("缺少请求上下文");
        }
        HttpServletRequest request = attributes.getRequest();
        try {
            UUID accountId = UUID.fromString(requireHeader(request, "X-Account-Id"));
            UUID schoolId = UUID.fromString(requireHeader(request, "X-School-Id"));
            AccountType type = AccountType.valueOf(
                    requireHeader(request, "X-Account-Type").toUpperCase(Locale.ROOT));
            return new CurrentUser(new AccountId(accountId), new SchoolId(schoolId), type);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw unauthorized("身份请求头非法");
        }
    }

    private String requireHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw unauthorized("缺少身份请求头 " + name);
        }
        return value;
    }

    private DomainException unauthorized(String message) {
        return new DomainException(ErrorCode.UNAUTHORIZED, message);
    }
}
