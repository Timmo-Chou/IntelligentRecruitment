package com.intelligentrecruitment.tenancy.application;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.boss.application.BossRequestContext;
import com.intelligentrecruitment.shared.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Resolves a Recruitment Tenant directly from BOSS. It intentionally writes no local organization,
 * membership or workspace projection: BOSS is the only authority for membership and seats.
 */
@Service
public class WorkspaceAccessService {
    private final BossControlPlaneClient boss;
    public WorkspaceAccessService(BossControlPlaneClient boss) { this.boss = boss; }

    public TenantScope requireBusinessAccess(UUID userId, UUID tenantId) {
        var tenant = boss.contexts(BossRequestContext.accessToken(userId)).tenants().stream()
                .filter(item -> item.tenantId().equals(tenantId) && "RECRUITMENT".equals(item.productDomain()))
                .findFirst().orElseThrow(() -> new ApiException("BOSS_CONTEXT_REQUIRED", "未找到可用的招聘产品租户", HttpStatus.FORBIDDEN));
        if ("PERSONAL".equals(tenant.tenantType())) {
            if (!"ACTIVE".equals(tenant.tenantStatus())) throw new ApiException("BOSS_TENANT_NOT_ACTIVE", "个人租户未启用", HttpStatus.FORBIDDEN);
        } else {
            if (!("TRIAL_ACTIVE".equals(tenant.tenantStatus()) || "ACTIVE".equals(tenant.tenantStatus()))) {
                throw new ApiException("BOSS_TENANT_NOT_ACTIVE", "企业租户当前不可使用招聘功能", HttpStatus.FORBIDDEN);
            }
            if (!tenant.seatAssigned()) throw new ApiException("BOSS_SEAT_REQUIRED", "当前账号未占用企业席位，无法使用招聘功能", HttpStatus.FORBIDDEN);
        }
        // Business services still expose a workspace-shaped method parameter for compatibility with
        // existing handlers; the value is always the BOSS Tenant UUID.
        return new TenantScope(tenantId, tenant.tenantType(), tenant.tenantName(), tenant.roleCode());
    }

    public record TenantScope(UUID tenantId, String type, String name, String role) { }
}
