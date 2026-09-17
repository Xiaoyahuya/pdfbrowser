package dev.pdfbrowser.service;

import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.AppUser;
import dev.pdfbrowser.repository.AppUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountPasswordService {

    private final AppUserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AccountPasswordService(
            AppUserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void changePassword(
            String username,
            String currentPassword,
            String newPassword
    ) {
        if (username == null || username.isBlank()) {
            throw new FileBrowserException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "请先登录"
            );
        }

        if (currentPassword == null
                || newPassword == null
                || currentPassword.isBlank()
                || newPassword.isBlank()) {
            throw new FileBrowserException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PASSWORD",
                    "密码不能为空"
            );
        }

        AppUser user = userRepository
                .findByUsername(username)
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> new FileBrowserException(
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED",
                        "请先登录"
                ));

        if (!passwordEncoder.matches(
                currentPassword,
                user.passwordHash()
        )) {
            throw new FileBrowserException(
                    HttpStatus.FORBIDDEN,
                    "CURRENT_PASSWORD_INCORRECT",
                    "当前密码不正确"
            );
        }

        if (currentPassword.equals(newPassword)) {
            throw new FileBrowserException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_UNCHANGED",
                    "新密码不能与当前密码相同"
            );
        }

        int updated = userRepository.updatePasswordHash(
                user.id(),
                passwordEncoder.encode(newPassword)
        );

        if (updated != 1) {
            throw new FileBrowserException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "PASSWORD_UPDATE_FAILED",
                    "密码修改失败"
            );
        }
    }
}