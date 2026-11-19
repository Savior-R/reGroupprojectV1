package com.shijiangtai.m1auth.application;

import com.shijiangtai.shared.application.CurrentUser;
import com.shijiangtai.shared.application.CurrentUserProvider;
import com.shijiangtai.shared.domain.DomainException;
import com.shijiangtai.shared.domain.ErrorCode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * JWT-backed {@link CurrentUserProvider} for M2–M7.
 *
 * <p>M1 的 JWT 是唯一可信身份来源。{@code M1PrincipalProvider} 在 {@code test / dev}
 * profile 下还会接受 {@code TestHeaderAuthFilter} 注入的 {@code HeaderIdentity}，因此既有
 * 集成测试不需要改；生产（默认 / docker）没有该兜底，只接受 JWT。
 *
 * <p>{@link CurrentUser} 要求非空 {@code schoolId}；管理员未建校时无法进入 M2–M7 数据接口，
 * 明确返回 403，而不是让下游出现空指针或跨校数据。
 */
@Component
@Primary
public class M1CurrentUserProvider implements CurrentUserProvider {

    private final M1PrincipalProvider principals;

    public M1CurrentUserProvider(M1PrincipalProvider principals) {
        this.principals = principals;
    }

    @Override
    public CurrentUser require() {
        M1Principal principal = principals.require();
        if (principal.schoolId() == null) {
            throw new DomainException(ErrorCode.FORBIDDEN, "管理员尚未建校");
        }
        return new CurrentUser(principal.accountId(), principal.schoolId(), principal.type());
    }
}
