package dev.pdfbrowser.service;

import dev.pdfbrowser.config.AccountProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class AccountPasswordService {
    private static final Pattern SAFE_USERNAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Set<PosixFilePermission> PASSWORD_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ);

    private final Path passwordFile;
    private final BCryptPasswordEncoder passwordEncoder;

    public AccountPasswordService(AccountProperties properties) {
        if (properties.getBcryptStrength() < 10 || properties.getBcryptStrength() > 15) {
            throw new IllegalArgumentException("bcrypt strength must be between 10 and 15");
        }
        this.passwordFile = properties.getHtpasswdFile().toAbsolutePath().normalize();
        this.passwordEncoder = new BCryptPasswordEncoder(properties.getBcryptStrength());
    }

    public synchronized void changePassword(String authenticatedUser, String currentPassword, String newPassword) {
        if (authenticatedUser == null || !SAFE_USERNAME.matcher(authenticatedUser).matches()) {
            throw new FileBrowserException(HttpStatus.FORBIDDEN, "AUTHENTICATED_USER_REQUIRED",
                    "无法确认当前登录用户");
        }
        if (containsLineBreak(currentPassword) || containsLineBreak(newPassword)) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD",
                    "密码不能包含换行符或空字符");
        }
        if (currentPassword.equals(newPassword)) {
            throw new FileBrowserException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED",
                    "新密码不能与当前密码相同");
        }

        try {
            List<String> existingLines = Files.readAllLines(passwordFile, StandardCharsets.UTF_8);
            List<String> updatedLines = new ArrayList<>(existingLines.size());
            boolean matchedUser = false;
            for (String line : existingLines) {
                int separator = line.indexOf(':');
                if (separator < 1 || !line.substring(0, separator).equals(authenticatedUser)) {
                    updatedLines.add(line);
                    continue;
                }
                if (matchedUser) {
                    continue;
                }
                String encodedPassword = line.substring(separator + 1);
                if (!encodedPassword.startsWith("$2") || !passwordEncoder.matches(currentPassword, encodedPassword)) {
                    throw new FileBrowserException(HttpStatus.FORBIDDEN, "CURRENT_PASSWORD_INCORRECT",
                            "当前密码不正确");
                }
                updatedLines.add(authenticatedUser + ":" + passwordEncoder.encode(newPassword));
                matchedUser = true;
            }
            if (!matchedUser) {
                throw new FileBrowserException(HttpStatus.FORBIDDEN, "CURRENT_PASSWORD_INCORRECT",
                        "当前密码不正确");
            }
            replacePasswordFile(updatedLines);
        } catch (FileBrowserException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new FileBrowserException(HttpStatus.INTERNAL_SERVER_ERROR, "PASSWORD_FILE_UNAVAILABLE",
                    "密码文件暂时不可用", exception);
        }
    }

    private void replacePasswordFile(List<String> lines) throws IOException {
        Path directory = passwordFile.getParent();
        if (directory == null) throw new IOException("Password file has no parent directory");
        Path temporary = Files.createTempFile(directory, ".htpasswd-", ".tmp");
        try {
            String content = String.join("\n", lines) + "\n";
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.setPosixFilePermissions(temporary, PASSWORD_FILE_PERMISSIONS);
            try {
                Files.move(temporary, passwordFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, passwordFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean containsLineBreak(String value) {
        return value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0;
    }
}
