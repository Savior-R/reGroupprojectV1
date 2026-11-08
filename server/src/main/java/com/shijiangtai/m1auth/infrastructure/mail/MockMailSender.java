package com.shijiangtai.m1auth.infrastructure.mail;

import com.shijiangtai.m1auth.application.port.MailSender;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发 / 测试用邮件实现：不真实发信，把消息留在内存供断言（03 §3.2.1）。
 * 生产不得启用（配置 {@code m1.mail.mock=false}）。
 */
public class MockMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(MockMailSender.class);

    private final List<SentMessage> messages = new CopyOnWriteArrayList<>();

    @Override
    public void send(String to, String subject, String textBody) {
        messages.add(new SentMessage(to, subject, textBody));
        // 只记录收件人与主题，正文含验证码，不进日志
        log.info("[mock-mail] to={} subject={}（正文含验证码，不打印）", to, subject);
    }

    public List<SentMessage> sentMessages() {
        return List.copyOf(messages);
    }

    public Optional<SentMessage> lastMessageTo(String to) {
        return messages.stream()
                .filter(message -> message.to().equals(to))
                .reduce((first, second) -> second);
    }

    /** 从最近一封邮件正文里抽出 6 位验证码，供集成测试断言。 */
    public Optional<String> lastCodeTo(String to) {
        return lastMessageTo(to).flatMap(message -> {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("\\b(\\d{6})\\b")
                            .matcher(message.body());
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        });
    }

    public void clear() {
        messages.clear();
    }

    public record SentMessage(String to, String subject, String body) {
    }
}
