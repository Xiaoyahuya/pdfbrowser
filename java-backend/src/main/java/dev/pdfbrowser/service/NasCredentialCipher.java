package dev.pdfbrowser.service;

import dev.pdfbrowser.config.NasMountProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class NasCredentialCipher {
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private final NasMountProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKeySpec key;

    public NasCredentialCipher(NasMountProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        if (!properties.isEnabled()) return;
        try {
            byte[] decoded = Base64.getDecoder().decode(properties.getCredentialKey().trim());
            if (decoded.length != 32) {
                throw new IllegalStateException("PDFBROWSER_NAS_CREDENTIAL_KEY 必须是 Base64 编码的 32 字节密钥");
            }
            key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PDFBROWSER_NAS_CREDENTIAL_KEY 不是有效的 Base64", exception);
        }
    }

    public String encrypt(String plaintext) {
        ensureReady();
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(iv) + "." + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("NAS 凭据加密失败", exception);
        }
    }

    public String decrypt(String value) {
        ensureReady();
        String[] parts = value == null ? new String[0] : value.split("\\.", 2);
        if (parts.length != 2) throw new IllegalStateException("NAS 凭据密文格式无效");
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] iv = decoder.decode(parts[0]);
            byte[] encrypted = decoder.decode(parts[1]);
            if (iv.length != IV_BYTES) throw new IllegalStateException("NAS 凭据密文 IV 无效");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("NAS 凭据解密失败", exception);
        }
    }

    private void ensureReady() {
        if (key == null) throw new IllegalStateException("NAS 凭据加密功能未启用");
    }
}
