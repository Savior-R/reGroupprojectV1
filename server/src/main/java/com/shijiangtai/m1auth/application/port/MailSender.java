package com.shijiangtai.m1auth.application.port;

/**
 * 邮件发送端口（03 §3.2.1）。dev / test 用 Mock 实现，不真实发信；
 * 生产用 SMTP。发送失败不得改变账号状态（返回 503 由上层映射）。
 */
public interface MailSender {

    /**
     * @throws MailSendException 发送失败（SMTP 不可用、认证失败等）
     */
    void send(String to, String subject, String textBody);

    /** 发送失败，映射为 {@code OAUTH_PROVIDER_UNAVAILABLE} / 503。 */
    class MailSendException extends RuntimeException {
        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }

        public MailSendException(String message) {
            super(message);
        }
    }
}
