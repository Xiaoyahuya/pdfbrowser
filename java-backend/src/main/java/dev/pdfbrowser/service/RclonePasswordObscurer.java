package dev.pdfbrowser.service;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class RclonePasswordObscurer {
    // Compatibility key defined by rclone's fs/config/obscure package.
    private static final byte[] RCLONE_KEY = new byte[] {
            (byte) 0x9c, (byte) 0x93, 0x5b, 0x48, 0x73, 0x0a, 0x55, 0x4d,
            0x6b, (byte) 0xfd, 0x7c, 0x63, (byte) 0xc8, (byte) 0x86, (byte) 0xa9, 0x2b,
            (byte) 0xd3, (byte) 0x90, 0x19, (byte) 0x8e, (byte) 0xb8, 0x12, (byte) 0x8a, (byte) 0xfb,
            (byte) 0xf4, (byte) 0xde, 0x16, 0x2b, (byte) 0x8b, (byte) 0x95, (byte) 0xf6, 0x38
    };
    private static final int IV_BYTES = 16;
    private final SecureRandom secureRandom = new SecureRandom();

    public String obscure(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(RCLONE_KEY, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(output);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法生成 rclone 兼容凭据", exception);
        }
    }
}
