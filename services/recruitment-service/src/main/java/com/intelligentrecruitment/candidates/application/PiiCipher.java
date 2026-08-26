package com.intelligentrecruitment.candidates.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PiiCipher {

    private static final int IV_LENGTH = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PiiCipher(@Value("${app.pii.encryption-key}") String configuredKey) {
        try {
            this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(configuredKey.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot initialize PII cipher", exception);
        }
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception exception) {
            throw cipherFailure();
        }
    }

    public String decrypt(String payload) {
        if (payload == null || payload.isBlank()) return "";
        try {
            byte[] bytes = Base64.getDecoder().decode(payload);
            byte[] iv = java.util.Arrays.copyOfRange(bytes, 0, IV_LENGTH);
            byte[] encrypted = java.util.Arrays.copyOfRange(bytes, IV_LENGTH, bytes.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw cipherFailure();
        }
    }

    private static ApiException cipherFailure() {
        return new ApiException("PII_CRYPTO_FAILED", "候选人敏感信息处理失败", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
