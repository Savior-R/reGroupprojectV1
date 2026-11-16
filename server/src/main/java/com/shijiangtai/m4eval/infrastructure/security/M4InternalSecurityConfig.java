package com.shijiangtai.m4eval.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Internal M4 evaluation API used only by the Python worker (06 §9.3).
 *
 * <p>Runs ahead of the shared chains and is limited to {@code /internal/evaluations/**}.
 */
@Configuration
@EnableWebSecurity
public class M4InternalSecurityConfig {

    @Bean
    @org.springframework.core.annotation.Order(0)
    public SecurityFilterChain m4InternalSecurityFilterChain(
            HttpSecurity http,
            @Value("${m4.service-token:${AI_SERVICE_TOKEN:dev-service-token}}")
            String serviceToken) throws Exception {
        http
                .securityMatcher("/internal/evaluations/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(
                        new M4ServiceTokenFilter(serviceToken),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    "{\"code\":\"UNAUTHORIZED\","
                                            + "\"message\":\"服务令牌无效\",\"details\":{}}");
                        }));
        return http.build();
    }
}
