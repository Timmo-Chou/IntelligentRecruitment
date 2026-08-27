package com.intelligentrecruitment.platform.admin.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

/**
 * 平台管理员管理服务。
 * 负责管理员的增删改查操作，包括创建、更新、禁用等。
 */
@Service
public class PlatformAdminService {

    private final JdbcTemplate jdbc;

    public PlatformAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询所有平台管理员列表。
     */
    public List<PlatformAdminRow> listAdmins() {
        return jdbc.query("""
                SELECT id, user_id, display_name, key_hash, role, status, created_at, updated_at
                FROM platform_admins
                ORDER BY created_at DESC
                """, (rs, n) -> new PlatformAdminRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("key_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        ));
    }

    /**
     * 获取单个管理员详情。
     */
    public PlatformAdminRow getAdmin(UUID adminId) {
        List<PlatformAdminRow> rows = jdbc.query("""
                SELECT id, user_id, display_name, key_hash, role, status, created_at, updated_at
                FROM platform_admins
                WHERE id = ?
                """, (rs, n) -> new PlatformAdminRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("key_hash"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        ), adminId);
        if (rows.isEmpty()) {
            throw new ApiException("ADMIN_NOT_FOUND", "平台管理员不存在", HttpStatus.NOT_FOUND);
        }
        return rows.getFirst();
    }

    /**
     * 创建新的平台管理员（独立账号，不关联用户表）。
     * 密钥使用 SHA-256 哈希存储。
     */
    @Transactional
    public PlatformAdminRow createAdmin(String displayName, String key, String role) {
        // 验证角色是否合法
        if (!List.of("SUPER_ADMIN", "PLATFORM_OPERATOR").contains(role)) {
            throw new ApiException("INVALID_ROLE", "角色不合法，仅支持 SUPER_ADMIN 或 PLATFORM_OPERATOR", HttpStatus.BAD_REQUEST);
        }

        // 验证密钥长度
        if (key == null || key.length() < 8) {
            throw new ApiException("INVALID_KEY", "密钥至少8位", HttpStatus.BAD_REQUEST);
        }

        Instant now = Instant.now();
        UUID adminId = UUID.randomUUID();
        String keyHash = sha256(key);

        jdbc.update("""
                INSERT INTO platform_admins (id, user_id, display_name, key_hash, role, status, created_at, updated_at)
                VALUES (?, NULL, ?, ?, ?, 'ACTIVE', ?, ?)
                """, adminId, required(displayName, "显示名称不能为空"), keyHash, role, timestamp(now), timestamp(now));

        return new PlatformAdminRow(adminId, null, displayName, keyHash, role, "ACTIVE", now, now);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * 更新管理员角色和状态。
     */
    @Transactional
    public PlatformAdminRow updateAdmin(UUID adminId, String role, String status) {
        PlatformAdminRow existing = getAdmin(adminId);

        if (role != null && !List.of("SUPER_ADMIN", "PLATFORM_OPERATOR").contains(role)) {
            throw new ApiException("INVALID_ROLE", "角色不合法，仅支持 SUPER_ADMIN 或 PLATFORM_OPERATOR", HttpStatus.BAD_REQUEST);
        }
        if (status != null && !List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new ApiException("INVALID_STATUS", "状态不合法，仅支持 ACTIVE 或 DISABLED", HttpStatus.BAD_REQUEST);
        }

        String newRole = role != null ? role : existing.role();
        String newStatus = status != null ? status : existing.status();
        Instant now = Instant.now();

        jdbc.update("""
                UPDATE platform_admins SET role = ?, status = ?, updated_at = ?
                WHERE id = ?
                """, newRole, newStatus, timestamp(now), adminId);

        return new PlatformAdminRow(adminId, existing.userId(), existing.displayName(), existing.keyHash(),
                newRole, newStatus, existing.createdAt(), now);
    }

    /**
     * 禁用管理员（设置状态为 DISABLED）。
     */
    @Transactional
    public void disableAdmin(UUID adminId) {
        PlatformAdminRow existing = getAdmin(adminId);
        if ("DISABLED".equals(existing.status())) {
            throw new ApiException("ADMIN_ALREADY_DISABLED", "该管理员已被禁用", HttpStatus.CONFLICT);
        }
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE platform_admins SET status = 'DISABLED', updated_at = ?
                WHERE id = ?
                """, timestamp(now), adminId);
    }

    /**
     * 删除管理员（物理删除）。
     */
    @Transactional
    public void deleteAdmin(UUID adminId) {
        // 校验管理员是否存在
        getAdmin(adminId);
        jdbc.update("DELETE FROM platform_admins WHERE id = ?", adminId);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    /**
     * 平台管理员数据行记录。
     */
    public record PlatformAdminRow(
            UUID id,
            UUID userId,
            String displayName,
            String keyHash,
            String role,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {}
}