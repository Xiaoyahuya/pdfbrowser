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

    private final Map<String, Deque<Instant>> ipRequests = new ConcurrentHashMap<>();

    public EmailVerificationCodeService(
            EmailVerificationCodeRepository codeRepository,
            AppUserRepository userRepository,
            EmailService emailService,
            BCryptPasswordEncoder passwordEncoder,
            VerificationProperties properties) {
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
            String clientIp) {
        String email = normalizeEmail(rawEmail);
        String purpose = normalizePurpose(rawPurpose);
        Instant now = Instant.now();

        if (!tryAcquireIp(clientIp, now)) {
            throw rateLimited();
        }

        Instant windowStart = now.minusSeconds(
                properties.getRateLimit().getWindowSeconds());

        long count = codeRepository.countCreatedSince(
                email,
                purpose,
                windowStart);

        if (count >= properties.getRateLimit().getMaxPerEmail()) {
            throw rateLimited();
        }

        Optional<Instant> latest = codeRepository.findLatestCreatedAt(email, purpose);

        if (latest.isPresent()
                && now.isBefore(
                        latest.get().plusSeconds(
                                properties.getResendIntervalSeconds()))) {
            throw new FileBrowserException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "VERIFICATION_CODE_TOO_FREQUENT",
                    "验证码发送过于频繁，请稍后再试");
        }

        if (userRepository.existsByEmail(email)) {
            return;
        }

        String code = generateCode();
        String codeHash = passwordEncoder.encode(code);

        Instant expiresAt = now.plusSeconds(
                properties.getCodeValiditySeconds());

        codeRepository.invalidateActive(email, purpose, now);

        codeRepository.insert(
                email,
                purpose,
                codeHash,
                expiresAt,
                now);

        emailService.sendVerificationCode(email, code);
    }

    @Transactional(noRollbackFor = FileBrowserException.class)
    public void verify(
            String rawEmail,
            String rawPurpose,
            String rawCode) {
        String email = normalizeEmail(rawEmail);
        String purpose = normalizePurpose(rawPurpose);
        String code = rawCode == null ? "" : rawCode.trim();

        if (!code.matches("\\d{6}")) {
            throw invalidCode();
        }

        Instant now = Instant.now();

        Optional<EmailVerificationCode> active = codeRepository.findActiveForUpdate(
                email,
                purpose,
                now,
                properties.getMaxAttempts());

        if (active.isEmpty()) {
            throw invalidCode();
        }

        EmailVerificationCode verificationCode = active.get();

        int updated = codeRepository.incrementAttempt(
                verificationCode.id(),
                now,
                properties.getMaxAttempts());

        if (updated != 1) {
            throw invalidCode();
        }

        if (!passwordEncoder.matches(
                code,
                verificationCode.codeHash())) {
            throw invalidCode();
        }

        if (codeRepository.consume(
                verificationCode.id(),
                now) != 1) {
            throw invalidCode();
        }
    }

    private boolean tryAcquireIp(
            String rawIp,
            Instant now) {
        String ip = rawIp == null || rawIp.isBlank()
                ? "unknown"
                : rawIp;

        Deque<Instant> requests = ipRequests.computeIfAbsent(
                ip,
                key -> new ArrayDeque<>());

        synchronized (requests) {
            Instant threshold = now.minusSeconds(
                    properties.getRateLimit().getWindowSeconds());

            while (!requests.isEmpty()
                    && requests.peekFirst().isBefore(threshold)) {
                requests.removeFirst();
            }

            if (requests.size() >= properties.getRateLimit().getMaxPerIp()) {
                return false;
            }

            requests.addLast(now);
            return true;
        }
    }

    private String generateCode() {
        return "%06d".formatted(
                ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new FileBrowserException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_EMAIL",
                    "邮箱地址无效");
        }

        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePurpose(String purpose) {
        if (!REGISTER_PURPOSE.equalsIgnoreCase(purpose)) {
            throw new FileBrowserException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_VERIFICATION_PURPOSE",
                    "验证码用途无效");
        }

        return REGISTER_PURPOSE;
    }

    private FileBrowserException invalidCode() {
        return new FileBrowserException(
                HttpStatus.BAD_REQUEST,
                "INVALID_VERIFICATION_CODE",
                "验证码无效或已过期");
    }

    private FileBrowserException rateLimited() {
        return new FileBrowserException(
                HttpStatus.TOO_MANY_REQUESTS,
                "VERIFICATION_RATE_LIMITED",
                "请求过于频繁，请稍后再试");
    }
}