package dev.pdfbrowser.service;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class RclonePasswordObscurerTest {
    private static final byte[] RCLONE_KEY = new byte[] {
            (byte) 0x9c, (byte) 0x93, 0x5b, 0x48, 0x73, 0x0a, 0x55, 0x4d,
            0x6b, (byte) 0xfd, 0x7c, 0x63, (byte) 0xc8, (byte) 0x86, (byte) 0xa9, 0x2b,
            (byte) 0xd3, (byte) 0x90, 0x19, (byte) 0x8e, (byte) 0xb8, 0x12, (byte) 0x8a, (byte) 0xfb,
            (byte) 0xf4, (byte) 0xde, 0x16, 0x2b, (byte) 0x8b, (byte) 0x95, (byte) 0xf6, 0x38
    };

    @Test
    void producesRcloneCompatibleAesCtrValue() throws Exception {
        String obscured = new RclonePasswordObscurer().obscure("中文-pass-123");
        byte[] value = Base64.getUrlDecoder().decode(obscured);
        byte[] iv = java.util.Arrays.copyOfRange(value, 0, 16);
        byte[] encrypted = java.util.Arrays.copyOfRange(value, 16, value.length);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(RCLONE_KEY, "AES"), new IvParameterSpec(iv));

        assertThat(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)).isEqualTo("中文-pass-123");
    }
}
