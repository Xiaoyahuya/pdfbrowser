package dev.pdfbrowser.service;

import dev.pdfbrowser.exception.FileBrowserException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String from;

    public EmailService(
            JavaMailSender mailSender,
            @Value("${spring.mail.from:}") String from
    ) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendVerificationCode(
            String email,
            String code
    ) {
        if (from == null || from.isBlank()) {
            throw new FileBrowserException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MAIL_NOT_CONFIGURED",
                    "邮件服务暂时不可用"
            );
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("PDF Browser 注册验证码");
        message.setText(
                "您的注册验证码是：" + code + "\n"
                        + "验证码有效期为 10 分钟。\n"
                        + "如果不是本人操作，请忽略此邮件。"
        );

        try {
            mailSender.send(message);
        } catch (MailException exception) {
            throw new FileBrowserException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "MAIL_SEND_FAILED",
                    "验证码邮件发送失败",
                    exception
            );
        }
    }
}
