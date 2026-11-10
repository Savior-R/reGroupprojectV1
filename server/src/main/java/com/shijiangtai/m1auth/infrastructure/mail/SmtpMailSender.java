package com.shijiangtai.m1auth.infrastructure.mail;

import com.shijiangtai.m1auth.application.port.MailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP 实现（FR-M1-09）。连接信息来自 {@code spring.mail.*}，即
 * {@code M1_MAIL_*} 环境变量；发送失败映射为 503，不改变账号状态（03 §3.2.1）。
 */
public class SmtpMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpMailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String subject, String textBody) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (from != null && !from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(to);
        message.setSubject(subject);
        message.setText(textBody);
        try {
            mailSender.send(message);
            log.info("验证码邮件已发送 to={}", to);
        } catch (MailException e) {
            // 不打印正文，避免验证码进日志
            log.warn("验证码邮件发送失败 to={} cause={}", to, e.getClass().getSimpleName());
            throw new MailSendException("邮件服务不可用", e);
        }
    }
}
