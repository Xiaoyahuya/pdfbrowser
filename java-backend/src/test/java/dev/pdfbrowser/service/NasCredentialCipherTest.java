package dev.pdfbrowser.service;

import dev.pdfbrowser.config.NasMountProperties;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NasCredentialCipherTest {
    @Test
    void encryptsWithRandomIvAndDetectsTampering() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        NasMountProperties properties = new NasMountProperties();
        properties.setCredentialKey(Base64.getEncoder().encodeToString(key));
        NasCredentialCipher cipher = new NasCredentialCipher(properties);
        cipher.initialize();

        String first = cipher.encrypt("nas-password");
        String second = cipher.encrypt("nas-password");

        assertThat(first).isNotEqualTo(second).doesNotContain("nas-password");
        assertThat(cipher.decrypt(first)).isEqualTo("nas-password");
        String[] parts = first.split("\\.", 2);
        byte[] tamperedBytes = Base64.getUrlDecoder().decode(parts[1]);
        tamperedBytes[tamperedBytes.length - 1] ^= 0x01;
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(tamperedBytes);
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }
}
