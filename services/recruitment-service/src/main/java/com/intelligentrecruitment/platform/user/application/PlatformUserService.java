package com.intelligentrecruitment.platform.user.application;

import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 平台用户管理服务：所有用户数据均从 BOSS 内部接口获取，不再依赖本地投影表。
 */
@Service
public class PlatformUserService {

    private final BossControlPlaneClient boss;

    public PlatformUserService(BossControlPlaneClient boss) {
        this.boss = boss;
    }

    public record UserSummary(
            String userId, String displayName, String phone, String status,
            String verificationStatus, String createdAt) {}

    public record UserDetail(
            String userId, String displayName, String phone, String status,
            String verificationStatus, String realNameMasked,
            String identityHash, String reviewedBy, String reviewedAt, String rejectionReason,
            List<TenantMembership> tenantMemberships,
            String createdAt) {}

    public record TenantMembership(String tenantId, String tenantName, String role, String status) {}

    public record PagedResult<T>(List<T> items, long total, int page, int pageSize) {}

    public PagedResult<UserSummary> listUsers(String search, String status, int page, int pageSize) {
        JsonNode result = boss.internalUserList(search, status, page, pageSize);
        long total = result.path("total").asLong(0);
        int safePage = result.path("page").asInt(page);
        int safeSize = result.path("page_size").asInt(pageSize);

        List<UserSummary> items = new ArrayList<>();
        for (JsonNode row : result.path("items")) {
            items.add(new UserSummary(
                    text(row, "user_id"),
                    text(row, "display_name"),
                    text(row, "phone_last_four"),
                    text(row, "status"),
                    text(row, "verification_status"),
                    text(row, "created_at")
            ));
        }
        return new PagedResult<>(items, total, safePage, safeSize);
    }

    public UserDetail getUserDetail(UUID userId) {
        JsonNode result = boss.internalUserDetail(userId);
        JsonNode u = result.path("user");
        if (u.isNull() || u.isMissingNode()) {
            throw new ApiException("NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        }

        List<TenantMembership> tenantMemberships = new ArrayList<>();
        for (JsonNode membership : result.path("memberships")) {
            String tenantId = text(membership, "tenant_id");
            String tenantName = text(membership, "tenant_name");
            String role = text(membership, "role");
            String membershipStatus = text(membership, "status");
            tenantMemberships.add(new TenantMembership(tenantId, tenantName, role, membershipStatus));
        }

        return new UserDetail(
                text(u, "user_id"),
                text(u, "display_name"),
                text(u, "phone_last_four"),
                text(u, "status"),
                text(u, "verification_status"),
                text(u, "real_name_masked"),
                text(u, "identity_hash"),
                text(u, "reviewed_by"),
                text(u, "reviewed_at"),
                text(u, "rejection_reason"),
                tenantMemberships,
                text(u, "created_at")
        );
    }

    public void disableUser(UUID userId) {
        boss.internalDisableUser(userId);
    }

    public void enableUser(UUID userId) {
        boss.internalEnableUser(userId);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }
}
