package dev.pdfbrowser.web;

import dev.pdfbrowser.exception.FileBrowserException;
import dev.pdfbrowser.model.PasswordChangeRequest;
import dev.pdfbrowser.model.PasswordChangeResponse;
import dev.pdfbrowser.service.AccountPasswordService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
public class AccountController {
    private static final String ACTION_HEADER = "X-PDFBrowser-Action";
    private static final String AUTHENTICATED_USER_HEADER = "X-Authenticated-User";
    private final AccountPasswordService passwordService;

    public AccountController(AccountPasswordService passwordService) {
        this.passwordService = passwordService;
    }

    @PostMapping("/password")
    public ResponseEntity<PasswordChangeResponse> changePassword(
            @Valid @RequestBody PasswordChangeRequest request,
            @RequestHeader(value = ACTION_HEADER, required = false) String action,
            @RequestHeader(value = AUTHENTICATED_USER_HEADER, required = false) String authenticatedUser,
            HttpServletRequest servletRequest) {
        requireSecureRequest(action, servletRequest);
        passwordService.changePassword(authenticatedUser, request.currentPassword(), request.newPassword());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PasswordChangeResponse(true, "密码修改成功，请使用新密码重新登录"));
    }

    private void requireSecureRequest(String action, HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        boolean https = request.isSecure() || "https".equalsIgnoreCase(forwardedProto);
        if (!https) {
            throw new FileBrowserException(HttpStatus.UPGRADE_REQUIRED, "HTTPS_REQUIRED",
                    "只能通过 HTTPS 修改密码");
        }
        if (!"password-change".equals(action)) {
            throw new FileBrowserException(HttpStatus.FORBIDDEN, "PASSWORD_ACTION_HEADER_REQUIRED",
                    "修改密码请求缺少安全标记");
        }
    }
}
