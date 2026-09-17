package dev.pdfbrowser.config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration 
public class PasswordEncoderConfig {
    @Bean // 把密码加密器注册到 Spring 容器
    public BCryptPasswordEncoder passwordEncoder(
            AccountProperties properties) {
        // 读取 application.yml 中的 BCrypt 加密强度
        int strength = properties.getBcryptStrength();
        // BCrypt 强度必须在 10 到 15 之间
        if (strength < 10 || strength > 15) {
            throw new IllegalArgumentException(
                "bcrypt strength must be between 10 and 15"
            );
        }
        // 创建密码加密器，并交给 Spring 管理
        return new BCryptPasswordEncoder(strength);
    }
}