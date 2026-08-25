package com.intelligentrecruitment.shared.security;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Component
public class PlatformAdminGuard {
    private final String configuredKey;

    public PlatformAdminGuard(@Value("${app.platform-admin-key}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    public void require(String suppliedKey) {
        if (suppliedKey == null || !MessageDigest.isEqual(configuredKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException("PLATFORM_ADMIN_REQUIRED", "需要平台审核权限", HttpStatus.FORBIDDEN);
        }
    }
}
