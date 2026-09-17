package com.intelligentrecruitment.tenancy.application;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.boss.application.BossRequestContext;
import com.intelligentrecruitment.shared.error.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Resolves a Recruitment Tenant directly from BOSS. It intentionally writes no local organization,
 * membership or tenant projection: BOSS is the only authority for membership and seats.
 */
@Service
public class TenantAccessService {
    private final BossControlPlaneClient boss;
    public TenantAccessService(BossControlPlaneClient boss) { this.boss = boss; }

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
        var overview = boss.tenantOverview(BossRequestContext.accessToken(userId), tenantId);
        // Business services still expose a tenant-shaped method parameter for compatibility with
        // existing handlers; the value is always the BOSS Tenant UUID.
        return new TenantScope(tenantId, tenant.tenantType(), tenant.tenantName(), tenant.roleCode(), tenant.owner(),
                overview.talentPoolSharingEnabled(), overview.jobPoolSharingEnabled(), false);
    }

    /**
     * Expired enterprise owners retain the agreed 30-day read/export window for
     * enterprise pools only. No source-data or membership operation uses this scope.
     */
    public TenantScope requireEnterprisePoolReadAccess(UUID userId, UUID tenantId) {
        var tenant = boss.contexts(BossRequestContext.accessToken(userId)).tenants().stream()
                .filter(item -> item.tenantId().equals(tenantId) && "RECRUITMENT".equals(item.productDomain()))
                .findFirst().orElseThrow(() -> new ApiException("BOSS_CONTEXT_REQUIRED", "未找到可用的招聘产品租户", HttpStatus.FORBIDDEN));
        boolean active = "TRIAL_ACTIVE".equals(tenant.tenantStatus()) || "ACTIVE".equals(tenant.tenantStatus());
        boolean expiredOwner = "EXPIRED_READONLY".equals(tenant.tenantStatus()) && tenant.owner();
        if (!"ENTERPRISE".equals(tenant.tenantType()) || (!active && !expiredOwner) || (active && !tenant.seatAssigned())) {
            throw new ApiException("ENTERPRISE_POOL_READ_DENIED", "当前账号无权查看企业数据池", HttpStatus.FORBIDDEN);
        }
        var overview = boss.tenantOverview(BossRequestContext.accessToken(userId), tenantId);
        return new TenantScope(tenantId, tenant.tenantType(), tenant.tenantName(), tenant.roleCode(), tenant.owner(),
                overview.talentPoolSharingEnabled(), overview.jobPoolSharingEnabled(), expiredOwner);
    }

    public record TenantScope(UUID tenantId, String type, String name, String role, boolean owner,
                              boolean talentPoolSharingEnabled, boolean jobPoolSharingEnabled, boolean readOnly) {
        public TenantScope(UUID tenantId, String type, String name, String role) {
            this(tenantId, type, name, role, false, false, false, false);
        }
        public boolean enterprise() { return "ENTERPRISE".equals(type); }
    }
}
