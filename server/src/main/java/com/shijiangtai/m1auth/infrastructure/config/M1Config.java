package com.shijiangtai.m1auth.infrastructure.config;

import com.shijiangtai.m1auth.application.port.MailSender;
import com.shijiangtai.m1auth.application.port.OAuthProvider;
import com.shijiangtai.m1auth.application.port.OAuthStateStore;
import com.shijiangtai.m1auth.application.port.PasswordHasher;
import com.shijiangtai.m1auth.domain.VerificationCodeHasher;
import com.shijiangtai.m1auth.infrastructure.mail.MockMailSender;
import com.shijiangtai.m1auth.infrastructure.mail.SmtpMailSender;
import com.shijiangtai.m1auth.infrastructure.oauth.InMemoryOAuthStateStore;
import com.shijiangtai.m1auth.infrastructure.oauth.MockWeChatProvider;
import com.shijiangtai.m1auth.infrastructure.oauth.RedisOAuthStateStore;
import com.shijiangtai.m1auth.infrastructure.oauth.WeChatOAuthProvider;
import com.shijiangtai.m1auth.infrastructure.security.BCryptPasswordHasher;
import com.shijiangtai.m1auth.infrastructure.security.HmacVerificationCodeHasher;
import com.shijiangtai.m1auth.infrastructure.security.JwtTokenIssuer;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import tools.jackson.databind.ObjectMapper;

/**
 * M1 装配：按配置在 Mock 与真实实现之间切换（03 §3.10「测试 profile 用内存 / Mock」）。
 */
@Configuration
@EnableConfigurationProperties(M1Properties.class)
public class M1Config {

    @Bean
    @ConditionalOnMissingBean
    public Clock m1Clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordHasher passwordHasher() {
        return new BCryptPasswordHasher();
    }

    @Bean
    public VerificationCodeHasher verificationCodeHasher(M1Properties properties) {
        return new HmacVerificationCodeHasher(properties.emailCode().secret());
    }

    @Bean
    public JwtTokenIssuer jwtTokenIssuer(M1Properties properties, Clock clock) {
        return new JwtTokenIssuer(
                properties.jwt().secret(), properties.jwt().ttl(), clock);
    }

    // ------------------------------------------------------------------- mail

    @Bean
    @ConditionalOnProperty(name = "m1.mail.mock", havingValue = "true")
    public MailSender mockMailSender() {
        return new MockMailSender();
    }

    @Bean
    @ConditionalOnProperty(
            name = "m1.mail.mock", havingValue = "false", matchIfMissing = true)
    public MailSender smtpMailSender(JavaMailSender javaMailSender, M1Properties properties) {
        return new SmtpMailSender(javaMailSender, properties.mail().from());
    }

    // ---------------------------------------------------------------- wechat

    @Bean
    @ConditionalOnProperty(
            name = "m1.wechat.mock", havingValue = "true", matchIfMissing = true)
    public OAuthProvider mockWeChatProvider() {
        return new MockWeChatProvider();
    }

    @Bean
    @ConditionalOnProperty(name = "m1.wechat.mock", havingValue = "false")
    public OAuthProvider weChatOAuthProvider(
            M1Properties properties, ObjectMapper objectMapper) {
        return new WeChatOAuthProvider(
                properties.wechat().appId(),
                properties.wechat().appSecret(),
                properties.wechat().redirectUri(),
                objectMapper);
    }

    // ----------------------------------------------------------- state store

    @Bean
    @ConditionalOnProperty(name = "m1.oauth.store", havingValue = "redis")
    public OAuthStateStore redisOAuthStateStore(StringRedisTemplate redis, Clock clock) {
        return new RedisOAuthStateStore(redis, clock);
    }

    @Bean
    @ConditionalOnProperty(
            name = "m1.oauth.store", havingValue = "memory", matchIfMissing = true)
    public OAuthStateStore inMemoryOAuthStateStore(Clock clock) {
        return new InMemoryOAuthStateStore(clock);
    }
}
