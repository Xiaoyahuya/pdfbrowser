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
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthUserResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        AuthUserResponse user = authService.authenticate(request);

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

        return ResponseEntity.ok(user);
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

        new SecurityContextLogoutHandler().logout(
                servletRequest,
                servletResponse,
                authentication
        );

        new CookieClearingLogoutHandler("JSESSIONID").logout(
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
                authService.currentUser(authentication.getName())
        );
    }
}