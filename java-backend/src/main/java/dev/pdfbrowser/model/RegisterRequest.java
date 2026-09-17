package dev.pdfbrowser.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest (
    @NotBlank
    @Size(min = 3, max = 100)
    @Pattern(regexp = "[A-Za-z0-9._-]{3,100}")
    String username, // 用户名

    @NotBlank
    @Email
    @Size(max = 320)
    String email, // 邮箱

    @NotBlank
    @Size(min = 12, max = 128)
    String password, // 明文密码，只在请求处理过程中使用
    
    @NotBlank
    @Pattern(regexp = "\\d{6}")
    String verificationCode
){}
