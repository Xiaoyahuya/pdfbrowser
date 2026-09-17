package dev.pdfbrowser.service;

import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.AppUser;
import dev.pdfbrowser.model.AuthUserResponse;
import dev.pdfbrowser.model.LoginRequest;
import dev.pdfbrowser.model.RegisterRequest;
import dev.pdfbrowser.repository.AppUserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailVerificationCodeService verificationCodeService;
    private final String dummyPasswordHash;

    public AuthService(
            AppUserRepository userRepository,
            BCryptPasswordEncoder passwordEncoder,
            EmailVerificationCodeService verificationCodeService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.verificationCodeService = verificationCodeService;
        this.dummyPasswordHash = passwordEncoder.encode(
                "never-use-this-password"
        );
    }

    @Transactional(noRollbackFor = FileBrowserException.class)
    public void register(RegisterRequest request) {
        if (request == null) {
            throw new FileBrowserException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_REGISTRATION",
                    "注册信息无效"
            );
        }

        String username = request.username().trim();
        String email = normalizeEmail(request.email());

        verificationCodeService.verify(
                email,
                EmailVerificationCodeService.REGISTER_PURPOSE,
                request.verificationCode()
        );

        if (userRepository.existsByUsernameOrEmail(username, email)) {
            throw new FileBrowserException(
                    HttpStatus.CONFLICT,
                    "USER_ALREADY_EXISTS",
                    "用户名或邮箱已经存在"
            );
        }

        String passwordHash = passwordEncoder.encode(
                request.password()
        );

        try {
            userRepository.insert(username, email, passwordHash);
        } catch (DuplicateKeyException exception) {
            throw new FileBrowserException(
                    HttpStatus.CONFLICT,
                    "USER_ALREADY_EXISTS",
                    "用户名或邮箱已经存在",
                    exception
            );
        }
    }

    public AuthUserResponse authenticate(LoginRequest request) {
        String login = request == null || request.login() == null
                ? ""
                : request.login().trim();

        String password = request == null || request.password() == null
                ? ""
                : request.password();

        AppUser user = userRepository
                .findByUsernameOrEmail(login)
                .orElse(null);

        String storedHash = user == null
                ? dummyPasswordHash
                : user.passwordHash();

        boolean passwordMatches = passwordEncoder.matches(
                password,
                storedHash
        );

        if (user == null
                || !passwordMatches
                || !"ACTIVE".equals(user.status())) {
            throw new FileBrowserException(
                    HttpStatus.UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "用户名或密码错误"
            );
        }

        userRepository.updateLastLoginAt(user.id(), Instant.now());
        return toResponse(user);
    }

    public AuthUserResponse currentUser(String username) {
        AppUser user = userRepository
                .findByUsername(username)
                .filter(item -> "ACTIVE".equals(item.status()))
                .orElseThrow(() -> new FileBrowserException(
                        HttpStatus.UNAUTHORIZED,
                        "UNAUTHORIZED",
                        "请先登录"
                ));

        return toResponse(user);
    }

    public AuthUserResponse toResponse(AppUser user) {
        return new AuthUserResponse(
                user.id(),
                user.username(),
                user.email(),
                user.status()
        );
    }

    private String normalizeEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new FileBrowserException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EMAIL",
                    "邮箱地址无效"
            );
        }

        return rawEmail.trim().toLowerCase(Locale.ROOT);
    }
}