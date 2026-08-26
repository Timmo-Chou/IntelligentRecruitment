package com.intelligentrecruitment.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class SecurityHashes {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecurityHashes() {
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormatHolder.HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class HexFormatHolder {
        private static final java.util.HexFormat HEX = java.util.HexFormat.of();
    }
}
