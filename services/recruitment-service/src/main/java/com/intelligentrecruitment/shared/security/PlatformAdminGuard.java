package com.intelligentrecruitment.shared.security;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 平台管理端鉴权守卫。
 * 同时负责：1) 验证平台管理员身份  2) 校验操作权限。
 * 权限定义硬编码在代码中，MVP 仅两个角色：SUPER_ADMIN 和 PLATFORM_OPERATOR。
 */
@Component
public class PlatformAdminGuard {

    private final String configuredKey;
    private final JdbcTemplate jdbc;

    // 超级管理员拥有全部权限
    private static final Set<String> SUPER_ADMIN_PERMISSIONS = Set.of(
            "admin:manage", "menu:manage", "user:read", "user:write",
            "company:read", "company:write", "verification:review",
            "membership:review", "ticket:read", "ticket:write",
            "billing:read", "billing:adjust",
            "pricing:read", "pricing:write"
    );

    // 平台运营权限
    private static final Set<String> OPERATOR_PERMISSIONS = Set.of(
            "user:read", "user:write", "company:read", "company:write",
            "verification:review", "membership:review",
            "ticket:read", "ticket:write",
            "billing:read", "billing:adjust",
            "pricing:read"
    );

    public PlatformAdminGuard(@Value("${app.platform-admin-key}") String configuredKey, JdbcTemplate jdbc) {
        this.configuredKey = configuredKey;
        this.jdbc = jdbc;
    }

    /**
     * 验证密钥，返回管理员身份。
     * 优先匹配共享密钥，其次匹配数据库中各管理员的独立密钥。
     */
    public PlatformAdminInfo authenticate(String suppliedKey) {
        if (suppliedKey == null) {
            throw new ApiException("PLATFORM_ADMIN_REQUIRED", "需要平台管理权限", HttpStatus.FORBIDDEN);
        }

        // 1. 先尝试匹配共享密钥（配置的全局密钥），始终返回 SUPER_ADMIN 身份
        if (configuredKey != null && !configuredKey.isBlank()
                && MessageDigest.isEqual(configuredKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            // 共享密钥具有最高权限，返回 SUPER_ADMIN 身份
            return BUILTIN_ADMIN;
        }

        // 2. 尝试匹配数据库中各管理员的独立密钥
        String keyHash = sha256(suppliedKey);
        List<PlatformAdminInfo> admins = jdbc.query(
                "SELECT id, user_id, display_name, role FROM platform_admins WHERE key_hash = ? AND status = 'ACTIVE'",
                (rs, n) -> new PlatformAdminInfo(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("role")
                ), keyHash);
        if (!admins.isEmpty()) {
            return admins.getFirst();
        }

        throw new ApiException("PLATFORM_ADMIN_REQUIRED", "需要平台管理权限", HttpStatus.FORBIDDEN);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** 内置默认超级管理员，在数据库无管理员时兜底 */
    private static final PlatformAdminInfo BUILTIN_ADMIN = new PlatformAdminInfo(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "内置管理员",
            "SUPER_ADMIN"
    );

    /**
     * 验证管理员是否有指定权限。
     */
    public void requirePermission(PlatformAdminInfo admin, String permissionCode) {
        if (admin == null) {
            throw new ApiException("PLATFORM_ADMIN_REQUIRED", "需要平台管理权限", HttpStatus.FORBIDDEN);
        }
        Set<String> permissions = "SUPER_ADMIN".equals(admin.role())
                ? SUPER_ADMIN_PERMISSIONS : OPERATOR_PERMISSIONS;
        if (!permissions.contains(permissionCode)) {
            throw new ApiException("PLATFORM_PERMISSION_DENIED",
                    "当前角色「" + admin.role() + "」没有权限「" + permissionCode + "」", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 向后兼容：仅验证密钥（不校验权限）。
     */
    public void require(String suppliedKey) {
        authenticate(suppliedKey);
    }

    /**
     * 平台管理员身份信息。
     */
    public record PlatformAdminInfo(UUID id, UUID userId, String displayName, String role) {}
}