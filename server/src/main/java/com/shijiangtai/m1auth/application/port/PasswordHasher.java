package com.shijiangtai.m1auth.application.port;

/** 密码哈希端口。实现为 BCrypt cost 12（03 §3.3、§9 Q6）。 */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String passwordHash);
}
