可以。下面从之前的第 7 项开始重新给出，`mapUser` 已经包含在 `AppUserRepository.java` 的完整代码中。

以下路径均以项目根目录为起点：

```text
/mnt/disks/linux400/home-lfp/Projects/pdfbrowser
```

本次只说明代码，不修改文件。

## 7. 用户模型和用户 Repository

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/model/AppUser.java
```

```java
package dev.pdfbrowser.model;

import java.time.Instant;

public record AppUser(
        long id,
        String username,
        String email,
        String passwordHash,
        String status,
        Instant lastLoginAt
) {
}
```

### 文件

先把目录名从：

```text
java-backend/src/main/java/dev/pdfbrowser/Repository
```

改为：

```text
java-backend/src/main/java/dev/pdfbrowser/repository
```

文件路径：

```text
java-backend/src/main/java/dev/pdfbrowser/repository/AppUserRepository.java
```

完整代码如下：

```java
package dev.pdfbrowser.repository;

import dev.pdfbrowser.model.AppUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Repository
public class AppUserRepository {

    private static final RowMapper<AppUser> ROW_MAPPER =
            AppUserRepository::mapUser;

    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsByUsernameOrEmail(
            String username,
            String email
    ) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM app_user
                    WHERE username = ?
                       OR lower(email) = lower(?)
                )
                """;

        Boolean result = jdbcTemplate.queryForObject(
                sql,
                Boolean.class,
                username,
                email
        );

        return Boolean.TRUE.equals(result);
    }

    public boolean existsByEmail(String email) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM app_user
                    WHERE lower(email) = lower(?)
                )
                """;

        Boolean result = jdbcTemplate.queryForObject(
                sql,
                Boolean.class,
                email
        );

        return Boolean.TRUE.equals(result);
    }

    public Optional<AppUser> findByUsernameOrEmail(
            String login
    ) {
        String sql = """
                SELECT id,
                       username,
                       email,
                       password_hash,
                       status,
                       last_login_at
                FROM app_user
                WHERE username = ?
                   OR lower(email) = lower(?)
                LIMIT 1
                """;

        return jdbcTemplate.query(
                        sql,
                        ROW_MAPPER,
                        login,
                        login
                )
                .stream()
                .findFirst();
    }

    public Optional<AppUser> findByUsername(
            String username
    ) {
        String sql = """
                SELECT id,
                       username,
                       email,
                       password_hash,
                       status,
                       last_login_at
                FROM app_user
                WHERE username = ?
                LIMIT 1
                """;

        return jdbcTemplate.query(
                        sql,
                        ROW_MAPPER,
                        username
                )
                .stream()
                .findFirst();
    }

    public long insert(
            String username,
            String email,
            String passwordHash
    ) {
        String sql = """
                INSERT INTO app_user
                    (username, email, password_hash, status)
                VALUES (?, ?, ?, 'ACTIVE')
                RETURNING id
                """;

        Long id = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                username,
                email,
                passwordHash
        );

        return Objects.requireNonNull(id);
    }

    public int updateLastLoginAt(
            long userId,
            Instant time
    ) {
        String sql = """
                UPDATE app_user
                SET last_login_at = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;

        return jdbcTemplate.update(
                sql,
                Timestamp.from(time),
                userId
        );
    }

    public int updatePasswordHash(
            long userId,
            String passwordHash
    ) {
        String sql = """
                UPDATE app_user
                SET password_hash = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'ACTIVE'
                """;

        return jdbcTemplate.update(
                sql,
                passwordHash,
                userId
        );
    }

    /*
     * 这个方法就是 ROW_MAPPER 中引用的 mapUser。
     *
     * 它负责把数据库查询结果的一行，
     * 转换成一个 AppUser 对象。
     */
    private static AppUser mapUser(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new AppUser(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                resultSet.getString("email"),
                resultSet.getString("password_hash"),
                resultSet.getString("status"),
                readInstant(resultSet, "last_login_at")
        );
    }

    private static Instant readInstant(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);

        return timestamp == null
                ? null
                : timestamp.toInstant();
    }
}
```

这两行：

```java
private static final RowMapper<AppUser> ROW_MAPPER =
        AppUserRepository::mapUser;
```

等价于：

```java
private static final RowMapper<AppUser> ROW_MAPPER =
        (resultSet, rowNumber) -> mapUser(resultSet, rowNumber);
```

`mapUser` 就是当前这个 `AppUserRepository.java` 文件里面定义的私有静态方法。

`AuthService.java` 中的 import 也要改成小写目录：

```java
import dev.pdfbrowser.repository.AppUserRepository;
```

## 8. 验证码模型和 Repository

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/model/EmailVerificationCode.java
```

```java
package dev.pdfbrowser.model;

import java.time.Instant;

public record EmailVerificationCode(
        long id,
        String email,
        String purpose,
        String codeHash,
        Instant expiresAt,
        Instant consumedAt,
        int attemptCount,
        Instant createdAt
) {
}
```

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/repository/EmailVerificationCodeRepository.java
```

```java
package dev.pdfbrowser.repository;

import dev.pdfbrowser.model.EmailVerificationCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Repository
public class EmailVerificationCodeRepository {

    private static final RowMapper<EmailVerificationCode> ROW_MAPPER =
            EmailVerificationCodeRepository::mapCode;

    private final JdbcTemplate jdbcTemplate;

    public EmailVerificationCodeRepository(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int invalidateActive(
            String email,
            String purpose,
            Instant now
    ) {
        String sql = """
                UPDATE email_verification_code
                SET consumed_at = ?
                WHERE email = ?
                  AND purpose = ?
                  AND consumed_at IS NULL
                """;

        return jdbcTemplate.update(
                sql,
                Timestamp.from(now),
                email,
                purpose
        );
    }

    public Optional<Instant> findLatestCreatedAt(
            String email,
            String purpose
    ) {
        String sql = """
                SELECT created_at
                FROM email_verification_code
                WHERE email = ?
                  AND purpose = ?
                ORDER BY created_at DESC
                LIMIT 1
                """;

        return jdbcTemplate.query(
                        sql,
                        (resultSet, rowNumber) ->
                                resultSet
                                        .getTimestamp("created_at")
                                        .toInstant(),
                        email,
                        purpose
                )
                .stream()
                .findFirst();
    }

    public long countCreatedSince(
            String email,
            String purpose,
            Instant since
    ) {
        String sql = """
                SELECT COUNT(*)
                FROM email_verification_code
                WHERE email = ?
                  AND purpose = ?
                  AND created_at >= ?
                """;

        Long count = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                email,
                purpose,
                Timestamp.from(since)
        );

        return count == null ? 0 : count;
    }

    public long insert(
            String email,
            String purpose,
            String codeHash,
            Instant expiresAt,
            Instant createdAt
    ) {
        String sql = """
                INSERT INTO email_verification_code
                    (email, purpose, code_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """;

        Long id = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                email,
                purpose,
                codeHash,
                Timestamp.from(expiresAt),
                Timestamp.from(createdAt)
        );

        return Objects.requireNonNull(id);
    }

    public Optional<EmailVerificationCode> findActiveForUpdate(
            String email,
            String purpose,
            Instant now,
            int maxAttempts
    ) {
        String sql = """
                SELECT id,
                       email,
                       purpose,
                       code_hash,
                       expires_at,
                       consumed_at,
                       attempt_count,
                       created_at
                FROM email_verification_code
                WHERE email = ?
                  AND purpose = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                  AND attempt_count < ?
                ORDER BY created_at DESC
                LIMIT 1
                FOR UPDATE
                """;

        return jdbcTemplate.query(
                        sql,
                        ROW_MAPPER,
                        email,
                        purpose,
                        Timestamp.from(now),
                        maxAttempts
                )
                .stream()
                .findFirst();
    }

    public int incrementAttempt(
            long id,
            Instant now,
            int maxAttempts
    ) {
        String sql = """
                UPDATE email_verification_code
                SET attempt_count = attempt_count + 1
                WHERE id = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                  AND attempt_count < ?
                """;

        return jdbcTemplate.update(
                sql,
                id,
                Timestamp.from(now),
                maxAttempts
        );
    }

    public int consume(
            long id,
            Instant now
    ) {
        String sql = """
                UPDATE email_verification_code
                SET consumed_at = ?
                WHERE id = ?
                  AND consumed_at IS NULL
                  AND expires_at > ?
                """;

        return jdbcTemplate.update(
                sql,
                Timestamp.from(now),
                id,
                Timestamp.from(now)
        );
    }

    private static EmailVerificationCode mapCode(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new EmailVerificationCode(
                resultSet.getLong("id"),
                resultSet.getString("email"),
                resultSet.getString("purpose"),
                resultSet.getString("code_hash"),
                resultSet.getTimestamp("expires_at").toInstant(),
                readInstant(resultSet, "consumed_at"),
                resultSet.getInt("attempt_count"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    private static Instant readInstant(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);

        return timestamp == null
                ? null
                : timestamp.toInstant();
    }
}
```

## 9. DTO 文件

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/model/VerificationCodeRequest.java
```

```java
package dev.pdfbrowser.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerificationCodeRequest(
        @NotBlank
        @Email
        @Size(max = 320)
        String email,

        @NotBlank
        @Pattern(regexp = "REGISTER")
        String purpose
) {
}
```

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/model/RegisterRequest.java
```

```java
package dev.pdfbrowser.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 100)
        @Pattern(regexp = "[A-Za-z0-9._-]{3,100}")
        String username,

        @NotBlank
        @Email
        @Size(max = 320)
        String email,

        @NotBlank
        @Size(min = 12, max = 128)
        String password,

        @NotBlank
        @Pattern(regexp = "\\d{6}")
        String verificationCode
) {
}
```

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/model/LoginRequest.java
```

```java
package dev.pdfbrowser.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank
        @Size(max = 320)
        String login,

        @NotBlank
        @Size(max = 128)
        String password
) {
}
```

## 10. 响应 DTO

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/model/AuthUserResponse.java
```

```java
package dev.pdfbrowser.model;

public record AuthUserResponse(
        long id,
        String username,
        String email,
        String status
) {
}
```

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/model/VerificationCodeResponse.java
```

```java
package dev.pdfbrowser.model;

public record VerificationCodeResponse(
        String message
) {
}
```

空的文件：

```text
java-backend/src/main/java/dev/pdfbrowser/model/LoginResponse.java
```

可以删除。登录接口直接返回 `AuthUserResponse`。

## 11. 邮件服务

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/service/EmailService.java
```

```java
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
```

## 12. 验证码服务

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/service/EmailVerificationCodeService.java
```

```java
package dev.pdfbrowser.service;

import dev.pdfbrowser.config.VerificationProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.EmailVerificationCode;
import dev.pdfbrowser.repository.AppUserRepository;
import dev.pdfbrowser.repository.EmailVerificationCodeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class EmailVerificationCodeService {

    public static final String REGISTER_PURPOSE = "REGISTER";

    private final EmailVerificationCodeRepository codeRepository;
    private final AppUserRepository userRepository;
    private final EmailService emailService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final VerificationProperties properties;

    private final Map<String, Deque<Instant>> ipRequests =
            new ConcurrentHashMap<>();

    public EmailVerificationCodeService(
            EmailVerificationCodeRepository codeRepository,
            AppUserRepository userRepository,
            EmailService emailService,
            BCryptPasswordEncoder passwordEncoder,
            VerificationProperties properties
    ) {
        this.codeRepository = codeRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Transactional
    public void issue(
            String rawEmail,
            String rawPurpose,
            String clientIp
    ) {
        String email = normalizeEmail(rawEmail);
        String purpose = normalizePurpose(rawPurpose);
        Instant now = Instant.now();

        if (!tryAcquireIp(clientIp, now)) {
            throw rateLimited();
        }

        Instant windowStart = now.minusSeconds(
                properties.getRateLimit().getWindowSeconds()
        );

        long count = codeRepository.countCreatedSince(
                email,
                purpose,
                windowStart
        );

        if (count >= properties.getRateLimit().getMaxPerEmail()) {
            throw rateLimited();
        }

        Optional<Instant> latest =
                codeRepository.findLatestCreatedAt(
                        email,
                        purpose
                );

        if (latest.isPresent()
                && now.isBefore(
                latest.get().plusSeconds(
                        properties.getResendIntervalSeconds()
                )
        )) {
            throw new FileBrowserException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "VERIFICATION_CODE_TOO_FREQUENT",
                    "验证码发送过于频繁，请稍后再试"
            );
        }

        /*
         * 已注册邮箱也返回同样的 202，
         * 但是不发送邮件，避免邮箱枚举。
         */
        if (userRepository.existsByEmail(email)) {
            return;
        }

        String code = generateCode();
        String codeHash = passwordEncoder.encode(code);

        Instant expiresAt = now.plusSeconds(
                properties.getCodeValiditySeconds()
        );

        codeRepository.invalidateActive(
                email,
                purpose,
                now
        );

        codeRepository.insert(
                email,
                purpose,
                codeHash,
                expiresAt,
                now
        );

        emailService.sendVerificationCode(
                email,
                code
        );
    }

    @Transactional
    public void verify(
            String rawEmail,
            String rawPurpose,
            String rawCode
    ) {
        String email = normalizeEmail(rawEmail);
        String purpose = normalizePurpose(rawPurpose);
        String code = rawCode == null
                ? ""
                : rawCode.trim();

        if (!code.matches("\\d{6}")) {
            throw invalidCode();
        }

        Instant now = Instant.now();

        Optional<EmailVerificationCode> active =
                codeRepository.findActiveForUpdate(
                        email,
                        purpose,
                        now,
                        properties.getMaxAttempts()
                );

        if (active.isEmpty()) {
            throw invalidCode();
        }

        EmailVerificationCode verificationCode = active.get();

        int updated = codeRepository.incrementAttempt(
                verificationCode.id(),
                now,
                properties.getMaxAttempts()
        );

        if (updated != 1) {
            throw invalidCode();
        }

        if (!passwordEncoder.matches(
                code,
                verificationCode.codeHash()
        )) {
            throw invalidCode();
        }

        if (codeRepository.consume(
                verificationCode.id(),
                now
        ) != 1) {
            throw invalidCode();
        }
    }

    private boolean tryAcquireIp(
            String rawIp,
            Instant now
    ) {
        String ip = rawIp == null || rawIp.isBlank()
                ? "unknown"
                : rawIp;

        Deque<Instant> requests =
                ipRequests.computeIfAbsent(
                        ip,
                        key -> new ArrayDeque<>()
                );

        synchronized (requests) {
            Instant threshold = now.minusSeconds(
                    properties.getRateLimit().getWindowSeconds()
            );

            while (!requests.isEmpty()
                    && requests.peekFirst().isBefore(threshold)) {
                requests.removeFirst();
            }

            if (requests.size()
                    >= properties.getRateLimit().getMaxPerIp()) {
                return false;
            }

            requests.addLast(now);
            return true;
        }
    }

    private String generateCode() {
        return "%06d".formatted(
                ThreadLocalRandom.current().nextInt(1_000_000)
        );
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw invalidCode();
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePurpose(String purpose) {
        if (!REGISTER_PURPOSE.equalsIgnoreCase(purpose)) {
            throw new FileBrowserException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_VERIFICATION_PURPOSE",
                    "验证码用途无效"
            );
        }

        return REGISTER_PURPOSE;
    }

    private FileBrowserException invalidCode() {
        return new FileBrowserException(
                HttpStatus.BAD_REQUEST,
                "INVALID_VERIFICATION_CODE",
                "验证码无效或已过期"
        );
    }

    private FileBrowserException rateLimited() {
        return new FileBrowserException(
                HttpStatus.TOO_MANY_REQUESTS,
                "VERIFICATION_RATE_LIMITED",
                "请求过于频繁，请稍后再试"
        );
    }
}
```

## 13. AuthService

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/service/AuthService.java
```

```java
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

import java.time.Instant;
import java.util.Locale;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final EmailVerificationCodeService verificationCodeService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;

    public AuthService(
            AppUserRepository userRepository,
            EmailVerificationCodeService verificationCodeService,
            BCryptPasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.verificationCodeService = verificationCodeService;
        this.passwordEncoder = passwordEncoder;

        this.dummyPasswordHash =
                passwordEncoder.encode("never-use-this-password");
    }

    public void register(RegisterRequest request) {
        String username = request.username().trim();
        String email = normalizeEmail(request.email());

        /*
         * 注册时必须先验证验证码。
         */
        verificationCodeService.verify(
                email,
                EmailVerificationCodeService.REGISTER_PURPOSE,
                request.verificationCode()
        );

        if (userRepository.existsByUsernameOrEmail(
                username,
                email
        )) {
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
            userRepository.insert(
                    username,
                    email,
                    passwordHash
            );
        } catch (DuplicateKeyException exception) {
            throw new FileBrowserException(
                    HttpStatus.CONFLICT,
                    "USER_ALREADY_EXISTS",
                    "用户名或邮箱已经存在",
                    exception
            );
        }
    }

    public AppUser authenticate(LoginRequest request) {
        String login = request.login() == null
                ? ""
                : request.login().trim();

        String password = request.password() == null
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

        userRepository.updateLastLoginAt(
                user.id(),
                Instant.now()
        );

        return user;
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

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
```

## 14. AuthController

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/web/AuthController.java
```

```java
package dev.pdfbrowser.web;

import dev.pdfbrowser.model.AuthUserResponse;
import dev.pdfbrowser.model.LoginRequest;
import dev.pdfbrowser.model.RegisterRequest;
import dev.pdfbrowser.model.VerificationCodeRequest;
import dev.pdfbrowser.model.VerificationCodeResponse;
import dev.pdfbrowser.service.AuthService;
import dev.pdfbrowser.service.EmailVerificationCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String GENERIC_CODE_MESSAGE =
            "如果请求有效，验证码已发送";

    private final AuthService authService;
    private final EmailVerificationCodeService verificationCodeService;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(
            AuthService authService,
            EmailVerificationCodeService verificationCodeService,
            SecurityContextRepository securityContextRepository
    ) {
        this.authService = authService;
        this.verificationCodeService = verificationCodeService;
        this.securityContextRepository = securityContextRepository;
    }

    /*
     * CSRF 辅助接口。
     * Vue 在登录、注册、上传等 POST 之前调用它。
     */
    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @PostMapping("/verification-codes")
    public ResponseEntity<VerificationCodeResponse> sendCode(
            @Valid @RequestBody VerificationCodeRequest request,
            HttpServletRequest servletRequest
    ) {
        verificationCodeService.issue(
                request.email(),
                request.purpose(),
                servletRequest.getRemoteAddr()
        );

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(new VerificationCodeResponse(
                        GENERIC_CODE_MESSAGE
                ));
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        authService.register(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthUserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        var user = authService.authenticate(request);

        servletRequest.getSession(true);
        servletRequest.changeSessionId();

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        user.username(),
                        null,
                        List.of(
                                new SimpleGrantedAuthority("ROLE_USER")
                        )
                );

        SecurityContext context =
                SecurityContextHolder.createEmptyContext();

        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        securityContextRepository.saveContext(
                context,
                servletRequest,
                servletResponse
        );

        return ResponseEntity.ok(
                authService.toResponse(user)
        );
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        new CookieClearingLogoutHandler("JSESSIONID")
                .logout(
                        servletRequest,
                        servletResponse,
                        authentication
                );

        new SecurityContextLogoutHandler()
                .logout(
                        servletRequest,
                        servletResponse,
                        authentication
                );

        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthUserResponse> me(
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                authService.currentUser(
                        authentication.getName()
                )
        );
    }
}
```

## 15. SecurityConfig

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/config/SecurityConfig.java
```

```java
package dev.pdfbrowser.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pdfbrowser.web.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository()
        );
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository
    ) throws Exception {
        http
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)

                .securityContext(context -> context
                        .securityContextRepository(
                                securityContextRepository
                        )
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/assets/**",
                                "/favicon.ico"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/csrf"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/verification-codes",
                                "/api/auth/register",
                                "/api/auth/login"
                        ).permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/auth/me"
                        ).authenticated()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/auth/logout"
                        ).authenticated()

                        .requestMatchers("/api/files/**")
                        .authenticated()

                        .requestMatchers("/api/nas/**")
                        .authenticated()

                        .requestMatchers("/api/account/**")
                        .authenticated()

                        .requestMatchers("/api/**")
                        .authenticated()

                        .anyRequest()
                        .permitAll()
                )

                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.IF_REQUIRED
                        )
                        .sessionFixation(fixation ->
                                fixation.changeSessionId()
                        )
                )

                .csrf(csrf -> csrf
                        .csrfTokenRepository(
                                CookieCsrfTokenRepository
                                        .withHttpOnlyFalse()
                        )
                )

                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(
                                (request, response, exception) ->
                                        writeError(
                                                request,
                                                response,
                                                401,
                                                "UNAUTHORIZED",
                                                "请先登录"
                                        )
                        )
                        .accessDeniedHandler(
                                (request, response, exception) ->
                                        writeError(
                                                request,
                                                response,
                                                403,
                                                "FORBIDDEN",
                                                "没有权限执行此操作"
                                        )
                        )
                );

        return http.build();
    }

    private void writeError(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(
                MediaType.APPLICATION_JSON_VALUE
        );
        response.setCharacterEncoding(
                StandardCharsets.UTF_8.name()
        );
        response.setHeader(
                "Cache-Control",
                "no-store"
        );

        objectMapper.writeValue(
                response.getWriter(),
                new ApiError(
                        Instant.now(),
                        status,
                        code,
                        message,
                        request.getRequestURI()
                )
        );
    }
}
```

## 16. 修改密码

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/service/AccountPasswordService.java
```

```java
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
```

### 文件

```text
java-backend/src/main/java/dev/pdfbrowser/web/AccountController.java
```

控制器中的修改密码方法改成：

```java
@PostMapping("/password")
public ResponseEntity<PasswordChangeResponse> changePassword(
        @Valid @RequestBody PasswordChangeRequest request,
        Authentication authentication,
        HttpServletRequest servletRequest
) {
    requireHttps(servletRequest);

    passwordService.changePassword(
            authentication.getName(),
            request.currentPassword(),
            request.newPassword()
    );

    return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(new PasswordChangeResponse(
                    true,
                    "密码修改成功，请使用新密码重新登录"
            ));
}

private void requireHttps(HttpServletRequest request) {
    String forwardedProto =
            request.getHeader("X-Forwarded-Proto");

    boolean https = request.isSecure()
            || "https".equalsIgnoreCase(forwardedProto);

    if (!https) {
        throw new FileBrowserException(
                HttpStatus.UPGRADE_REQUIRED,
                "HTTPS_REQUIRED",
                "只能通过 HTTPS 修改密码"
        );
    }
}
```

删除：

```java
@RequestHeader("X-Authenticated-User")
```

也删除：

```text
X-Authenticated-User
```

## 17. Nginx

文件：

```text
java-backend/deploy/pdfbrowser-nginx.conf
```

删除 server 级别的：

```nginx
auth_basic "PDF Browser";
auth_basic_user_file /etc/nginx/pdfbrowser-auth/htpasswd;
```

在 `location /` 之前增加：

```nginx
location ^~ /api/ {
    auth_basic off;

    proxy_pass http://127.0.0.1:18082;
    proxy_http_version 1.1;

    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    proxy_read_timeout 300s;
    proxy_request_buffering off;
    proxy_buffering off;
}
```

删除：

```nginx
proxy_set_header X-Authenticated-User $remote_user;
```

这样认证统一由 Spring Security 处理。

## 18. Vue 类型

修改：

```text
vue-frontend/src/types.ts
```

追加：

```ts
export interface AuthUserResponse {
  id: number
  username: string
  email: string
  status: string
}

export interface VerificationCodeRequest {
  email: string
  purpose: 'REGISTER'
}

export interface RegisterRequest {
  username: string
  email: string
  password: string
  verificationCode: string
}

export interface LoginRequest {
  login: string
  password: string
}

export interface ApiErrorBody {
  status?: number
  code?: string
  message?: string
  path?: string
}

export class ApiRequestError extends Error {
  constructor(
    message: string,
    public readonly status: number,
    public readonly code?: string,
  ) {
    super(message)
    this.name = 'ApiRequestError'
  }
}
```

## 19. Vue API

修改：

```text
vue-frontend/src/api.ts
```

加入以下 import：

```ts
import {
  ApiRequestError,
} from './types'

import type {
  ApiErrorBody,
  AuthUserResponse,
  DirectoryListing,
  FileEntry,
  LoginRequest,
  NasMount,
  NasMountInput,
  PasswordChangeResponse,
  RegisterRequest,
  SearchResponse,
  StorageCapabilities,
  VerificationCodeRequest,
} from './types'
```

加入通用请求方法：

```ts
const authBaseUrl = '/api/auth'

const SAFE_METHODS = new Set([
  'GET',
  'HEAD',
  'OPTIONS',
])

let csrfToken: string | null = null
let csrfRequest: Promise<void> | null = null

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') {
    return null
  }

  const prefix = `${name}=`

  const item = document.cookie
    .split('; ')
    .find((value) => value.startsWith(prefix))

  if (!item) return null

  return decodeURIComponent(item.slice(prefix.length))
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

async function readResponseBody(
  response: Response,
): Promise<unknown> {
  const text = await response.text()

  if (!text) return undefined

  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

function createApiError(
  response: Response,
  fallback: string,
  body: unknown,
): ApiRequestError {
  const apiBody = isRecord(body)
    ? body as ApiErrorBody
    : undefined

  const message = apiBody?.message
    || `${fallback}（${response.status}）`

  return new ApiRequestError(
    message,
    response.status,
    apiBody?.code,
  )
}

async function ensureCsrf(
  signal?: AbortSignal,
): Promise<void> {
  csrfToken = csrfToken || readCookie('XSRF-TOKEN')

  if (csrfToken) return

  if (!csrfRequest) {
    csrfRequest = (async () => {
      const response = await fetch(
        `${authBaseUrl}/csrf`,
        {
          method: 'GET',
          credentials: 'include',
          signal,
        },
      )

      const body = await readResponseBody(response)

      if (!response.ok) {
        throw createApiError(
          response,
          'CSRF 初始化失败',
          body,
        )
      }

      if (
        isRecord(body)
        && typeof body.token === 'string'
      ) {
        csrfToken = body.token
      }

      csrfToken = csrfToken || readCookie('XSRF-TOKEN')

      if (!csrfToken) {
        throw new ApiRequestError(
          'CSRF 初始化失败',
          response.status,
          'CSRF_TOKEN_MISSING',
        )
      }
    })().finally(() => {
      csrfRequest = null
    })
  }

  await csrfRequest
}

async function sessionFetch(
  url: string,
  init: RequestInit = {},
  signal?: AbortSignal,
): Promise<Response> {
  const method = (
    init.method || 'GET'
  ).toUpperCase()

  if (!SAFE_METHODS.has(method)) {
    await ensureCsrf(signal)
  }

  const headers = new Headers(init.headers)

  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json')
  }

  if (!SAFE_METHODS.has(method)) {
    const token = csrfToken || readCookie('XSRF-TOKEN')

    if (token) {
      headers.set('X-XSRF-TOKEN', token)
    }
  }

  return fetch(url, {
    ...init,
    headers,
    credentials: 'include',
    signal: init.signal || signal,
  })
}

async function request<T>(
  url: string,
  signal?: AbortSignal,
  init: RequestInit = {},
): Promise<T> {
  const response = await sessionFetch(
    url,
    init,
    signal,
  )

  const body = await readResponseBody(response)

  if (!response.ok) {
    throw createApiError(
      response,
      '请求失败',
      body,
    )
  }

  return body as T
}

async function requestText(
  url: string,
  signal?: AbortSignal,
  init: RequestInit = {},
): Promise<string> {
  const response = await sessionFetch(
    url,
    init,
    signal,
  )

  const text = await response.text()

  if (!response.ok) {
    let body: unknown

    try {
      body = text ? JSON.parse(text) : undefined
    } catch {
      body = text
    }

    throw createApiError(
      response,
      '请求失败',
      body,
    )
  }

  return text
}
```

加入认证 API：

```ts
export function sendVerificationCode(
  input: VerificationCodeRequest,
  signal?: AbortSignal,
) {
  return request<{ message: string }>(
    `${authBaseUrl}/verification-codes`,
    signal,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(input),
    },
  )
}

export function register(
  input: RegisterRequest,
  signal?: AbortSignal,
): Promise<void> {
  return request<void>(
    `${authBaseUrl}/register`,
    signal,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(input),
    },
  )
}

export function login(
  input: LoginRequest,
  signal?: AbortSignal,
): Promise<AuthUserResponse> {
  return request<AuthUserResponse>(
    `${authBaseUrl}/login`,
    signal,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(input),
    },
  )
}

export function logout(
  signal?: AbortSignal,
): Promise<void> {
  return request<void>(
    `${authBaseUrl}/logout`,
    signal,
    {
      method: 'POST',
    },
  )
}

export function getCurrentUser(
  signal?: AbortSignal,
): Promise<AuthUserResponse> {
  return request<AuthUserResponse>(
    `${authBaseUrl}/me`,
    signal,
  )
}
```

原来的上传、删除和 Markdown 请求要改成使用 `request()` 或 `requestText()`：

```ts
export async function uploadFile(
  path: string,
  file: File,
  signal?: AbortSignal,
): Promise<FileEntry> {
  const query = new URLSearchParams({ path })
  const body = new FormData()

  body.append('file', file)

  return request<FileEntry>(
    `${baseUrl}/upload?${query}`,
    signal,
    {
      method: 'POST',
      body,
    },
  )
}

export function loadMarkdown(
  path: string,
  signal?: AbortSignal,
): Promise<string> {
  const query = new URLSearchParams({ path })

  return requestText(
    `${baseUrl}/markdown?${query}`,
    signal,
    {
      cache: 'force-cache',
    },
  )
}

export async function removeNasMount(
  id: string,
  signal?: AbortSignal,
): Promise<void> {
  await request<void>(
    `${nasBaseUrl}/mounts/${encodeURIComponent(id)}`,
    signal,
    {
      method: 'DELETE',
      headers: {
        'X-PDFBrowser-Action': 'nas-mount',
      },
    },
  )
}
```

## 20. Vue 注册表单

文件：

```text
vue-frontend/src/components/RegisterForm.vue
```

核心逻辑：

```ts
const email = ref('')
const username = ref('')
const password = ref('')
const verificationCode = ref('')

const sendingCode = ref(false)
const submitting = ref(false)
const countdown = ref(0)

const emailPattern =
  /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const emailInvalid = computed(() =>
  emailTouched.value
  && !emailPattern.test(email.value.trim()),
)

const usernameInvalid = computed(() =>
  usernameTouched.value
  && username.value.trim().length < 3,
)

const passwordInvalid = computed(() =>
  passwordTouched.value
  && password.value.length < 12,
)

const codeInvalid = computed(() =>
  codeTouched.value
  && !/^\d{6}$/.test(
    verificationCode.value.trim(),
  ),
)

const canSendCode = computed(() =>
  emailPattern.test(email.value.trim())
  && !sendingCode.value
  && countdown.value === 0,
)
```

发送验证码：

```ts
async function sendCode() {
  emailTouched.value = true
  errorMessage.value = ''

  if (!canSendCode.value) return

  sendingCode.value = true

  try {
    await sendVerificationCode({
      email: email.value.trim(),
      purpose: 'REGISTER',
    })

    countdown.value = 60

    const timer = window.setInterval(() => {
      countdown.value -= 1

      if (countdown.value <= 0) {
        window.clearInterval(timer)
      }
    }, 1000)
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : '验证码发送失败'
  } finally {
    sendingCode.value = false
  }
}
```

提交注册：

```ts
async function submit() {
  emailTouched.value = true
  usernameTouched.value = true
  passwordTouched.value = true
  codeTouched.value = true
  errorMessage.value = ''

  if (
    emailInvalid.value
    || usernameInvalid.value
    || passwordInvalid.value
    || codeInvalid.value
  ) {
    return
  }

  submitting.value = true

  try {
    await register({
      username: username.value.trim(),
      email: email.value.trim(),
      password: password.value,
      verificationCode:
        verificationCode.value.trim(),
    })

    await router.push({ name: 'login' })
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : '注册失败'
  } finally {
    submitting.value = false
  }
}
```

用户名输入框必须是：

```vue
<Input
  id="username"
  v-model="username"
  type="text"
/>
```

验证码输入框：

```vue
<Input
  id="verification-code"
  v-model="verificationCode"
  type="text"
  inputmode="numeric"
  autocomplete="one-time-code"
  maxlength="6"
/>

<Button
  type="button"
  :disabled="!canSendCode"
  @click="sendCode"
>
  {{
    countdown > 0
      ? `${countdown}s`
      : sendingCode
        ? '发送中'
        : '发送验证码'
  }}
</Button>
```

用户名错误提示必须使用：

```vue
<FieldDescription v-if="usernameInvalid">
  用户名长度至少为 3 个字符
</FieldDescription>
```

不是：

```vue
<FieldDescription v-if="emailInvalid">
```

## 21. Vue 登录表单

文件：

```text
vue-frontend/src/components/LoginForm.vue
```

```ts
const loginName = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

async function submit() {
  errorMessage.value = ''

  if (!loginName.value.trim() || !password.value) {
    errorMessage.value = '请输入用户名和密码'
    return
  }

  submitting.value = true

  try {
    await login({
      login: loginName.value.trim(),
      password: password.value,
    })

    await router.push({ name: 'home' })
  } catch (error) {
    errorMessage.value =
      error instanceof Error
        ? error.message
        : '登录失败'
  } finally {
    submitting.value = false
  }
}
```

模板中的登录名必须是：

```vue
<Input
  id="login"
  v-model="loginName"
  type="text"
  autocomplete="username"
/>
```

密码：

```vue
<Input
  id="password"
  v-model="password"
  type="password"
  autocomplete="current-password"
/>
```

表单：

```vue
<form @submit.prevent="submit">
  <!-- 输入框 -->

  <p
    v-if="errorMessage"
    class="text-destructive text-sm"
    role="alert"
  >
    {{ errorMessage }}
  </p>

  <Button
    type="submit"
    :disabled="submitting"
  >
    {{ submitting ? '登录中' : '登录' }}
  </Button>
</form>
```

## 22. 路由守卫

文件：

```text
vue-frontend/src/router/index.ts
```

给 `/home` 增加：

```ts
meta: {
  requiresAuth: true,
}
```

增加守卫：

```ts
import { getCurrentUser } from '@/api'
import { ApiRequestError } from '@/types'

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) {
    return true
  }

  try {
    await getCurrentUser()
    return true
  } catch (error) {
    if (
      error instanceof ApiRequestError
      && [401, 403].includes(error.status)
    ) {
      return {
        name: 'login',
        query: {
          redirect: to.fullPath,
        },
      }
    }

    return {
      name: 'login',
    }
  }
})
```

## 23. HomeView 用户和退出

文件：

```text
vue-frontend/src/views/HomeView.vue
```

替换 import：

```ts
import {
  getCapabilities,
  getCurrentUser,
  logout,
} from '../api'

import { useRouter } from 'vue-router'
import type { AuthUserResponse } from '../types'
```

增加状态：

```ts
const router = useRouter()

const currentUser =
  ref<AuthUserResponse | null>(null)

const logoutPending = ref(false)
```

增加退出方法：

```ts
async function handleLogout() {
  logoutPending.value = true

  try {
    await logout()
  } finally {
    currentUser.value = null
    logoutPending.value = false

    await router.push({
      name: 'login',
    })
  }
}
```

在已有的 `onMounted()` 中加入：

```ts
try {
  currentUser.value = await getCurrentUser()
} catch {
  await router.push({
    name: 'login',
  })

  return
}
```

模板中加入：

```vue
<span
  v-if="currentUser"
  class="storage-status"
>
  {{ currentUser.username }}
</span>

<button
  class="nas-menu-button"
  type="button"
  :disabled="logoutPending"
  @click="handleLogout"
>
  {{ logoutPending ? '退出中' : '退出登录' }}
</button>
```

## 24. 环境变量示例

文件：

```text
java-backend/deploy/pdfbrowser.env.example
```

追加：

```dotenv
PDFBROWSER_SMTP_HOST=smtp.example.com
PDFBROWSER_SMTP_PORT=587
PDFBROWSER_SMTP_USERNAME=
PDFBROWSER_SMTP_PASSWORD=
PDFBROWSER_SMTP_FROM=

PDFBROWSER_SESSION_COOKIE_SECURE=true
PDFBROWSER_SESSION_COOKIE_SAME_SITE=lax

PDFBROWSER_CODE_VALIDITY_SECONDS=600
PDFBROWSER_CODE_RESEND_INTERVAL_SECONDS=60
PDFBROWSER_CODE_MAX_ATTEMPTS=5
PDFBROWSER_CODE_RATE_LIMIT_WINDOW_SECONDS=3600
PDFBROWSER_CODE_RATE_LIMIT_EMAIL=5
PDFBROWSER_CODE_RATE_LIMIT_IP=20
```

最后检查：

```bash
cd /mnt/disks/linux400/home-lfp/Projects/pdfbrowser/java-backend
./mvnw test

cd /mnt/disks/linux400/home-lfp/Projects/pdfbrowser/vue-frontend
npm test
```

其中 `mapUser` 的关键位置就是：

```text
java-backend/src/main/java/dev/pdfbrowser/repository/AppUserRepository.java
```

它位于 `ROW_MAPPER` 定义的下面或上面都可以，只要仍然在 `AppUserRepository` 类内部即可。