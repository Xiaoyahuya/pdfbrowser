package dev.pdfbrowser.service;

import dev.pdfbrowser.config.AccountProperties;
import dev.pdfbrowser.exception.FileBrowserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountPasswordServiceTest {
    private static final String CURRENT_PASSWORD = "Current-Password-123";
    private static final String NEW_PASSWORD = "New-Password-456!";

    @TempDir Path root;
    private Path passwordFile;
    private AccountPasswordService service;
    private BCryptPasswordEncoder encoder;

    @BeforeEach
    void setUp() throws Exception {
        passwordFile = root.resolve("htpasswd");
        encoder = new BCryptPasswordEncoder(10);
        Files.write(passwordFile, List.of(
                "other:" + encoder.encode("Other-Password-123"),
                "pdfbrowser:" + encoder.encode(CURRENT_PASSWORD)));
        AccountProperties properties = new AccountProperties();
        properties.setHtpasswdFile(passwordFile);
        properties.setBcryptStrength(10);
        service = new AccountPasswordService(properties);
    }

    @Test
    void replacesOnlyAuthenticatedUsersPassword() throws Exception {
        String otherLine = Files.readAllLines(passwordFile).get(0);

        service.changePassword("pdfbrowser", CURRENT_PASSWORD, NEW_PASSWORD);

        List<String> lines = Files.readAllLines(passwordFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo(otherLine);
        String newHash = lines.get(1).substring(lines.get(1).indexOf(':') + 1);
        assertThat(encoder.matches(NEW_PASSWORD, newHash)).isTrue();
        assertThat(encoder.matches(CURRENT_PASSWORD, newHash)).isFalse();
    }

    @Test
    void rejectsIncorrectCurrentPasswordWithoutChangingFile() throws Exception {
        String original = Files.readString(passwordFile);

        assertThatThrownBy(() -> service.changePassword("pdfbrowser", "Wrong-Password-000", NEW_PASSWORD))
                .isInstanceOfSatisfying(FileBrowserException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.code()).isEqualTo("CURRENT_PASSWORD_INCORRECT");
                });

        assertThat(Files.readString(passwordFile)).isEqualTo(original);
    }

    @Test
    void rejectsSpoofedOrMalformedUser() {
        assertThatThrownBy(() -> service.changePassword("pdfbrowser\nadmin", CURRENT_PASSWORD, NEW_PASSWORD))
                .isInstanceOfSatisfying(FileBrowserException.class, exception ->
                        assertThat(exception.code()).isEqualTo("AUTHENTICATED_USER_REQUIRED"));
    }

    @Test
    void rejectsPasswordReuse() {
        assertThatThrownBy(() -> service.changePassword("pdfbrowser", CURRENT_PASSWORD, CURRENT_PASSWORD))
                .isInstanceOfSatisfying(FileBrowserException.class, exception ->
                        assertThat(exception.code()).isEqualTo("PASSWORD_UNCHANGED"));
    }
}
