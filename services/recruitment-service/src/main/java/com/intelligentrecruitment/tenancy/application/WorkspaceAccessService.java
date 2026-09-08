package com.intelligentrecruitment.tenancy.application;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.boss.application.BossRequestContext;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Compatibility name retained while legacy business tables are migrated to BOSS contexts. */
@Service
public class WorkspaceAccessService {
    private final JdbcTemplate jdbc;
    private final BossControlPlaneClient boss;

    public WorkspaceAccessService(JdbcTemplate jdbc, BossControlPlaneClient boss) {
        this.jdbc = jdbc;
        this.boss = boss;
    }

    /** No local membership is consulted. BOSS context and BOSS RBAC decide every request. */
    public WorkspaceScope requireBusinessAccess(UUID userId, UUID contextId) {
        String token = BossRequestContext.accessToken(userId);
        BossControlPlaneClient.Contexts contexts = boss.contexts(token);
        BossControlPlaneClient.Company company = contexts.companies().stream()
                .filter(item -> item.companyId().equals(contextId)).findFirst().orElse(null);
        if (company == null) return personalScope(contexts, contextId, userId);
        if (!"ACTIVE".equals(company.companyStatus()) || !"ACTIVE".equals(company.tenantStatus())) {
            throw new ApiException("BOSS_COMPANY_NOT_ACTIVE", "所属企业或租户未启用", HttpStatus.FORBIDDEN);
        }
        if (!boss.permitted(userId, contextId, "company.job.read", null)) {
            throw new ApiException("BOSS_PERMISSION_DENIED", "当前 BOSS 角色无招聘工作台访问权限", HttpStatus.FORBIDDEN);
        }
        synchronizeProjection(company, userId);
        return new WorkspaceScope(contextId, contextId, company.entityType(), company.legalName(),
                company.companyOwner() ? "BOSS_COMPANY_OWNER" : "BOSS_COMPANY_MEMBER");
    }

    private WorkspaceScope personalScope(BossControlPlaneClient.Contexts contexts, UUID tenantId, UUID userId) {
        BossControlPlaneClient.Tenant tenant = contexts.tenants().stream()
                .filter(item -> item.tenantId().equals(tenantId) && "PERSONAL".equals(item.tenantType()))
                .findFirst()
                .orElseThrow(() -> new ApiException("BOSS_CONTEXT_REQUIRED", "未找到可用的 BOSS 个人上下文", HttpStatus.FORBIDDEN));
        if (!"ACTIVE".equals(tenant.tenantStatus())) {
            throw new ApiException("BOSS_TENANT_NOT_ACTIVE", "个人主体未启用", HttpStatus.FORBIDDEN);
        }
        synchronizePersonalProjection(tenant, userId);
        return new WorkspaceScope(tenantId, null, "PERSONAL", tenant.tenantName(), "BOSS_TENANT_OWNER");
    }

    private void synchronizeProjection(BossControlPlaneClient.Company company, UUID userId) {
        // These two rows are rebuildable compatibility projections for the legacy business
        // schema.  They do not participate in authentication, membership or authorization;
        // those decisions were already made by BOSS above.
        jdbc.update("""
                INSERT INTO users(id,phone_hash,phone_last_four,display_name,status,created_at,updated_at)
                VALUES(?,?,'0000',NULL,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON CONFLICT(id) DO UPDATE SET status='ACTIVE',updated_at=CURRENT_TIMESTAMP
                """, userId, SecurityHashes.sha256("BOSS_USER:" + userId));
        jdbc.update("""
                INSERT INTO companies(id,legal_name,display_name,credit_code_hash,credit_code_masked,
                                      verification_status,management_status,owner_user_id,created_at,updated_at)
                VALUES(?,?,?,?,'BOSS-PROJECTION','VERIFIED','USER_MANAGED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON CONFLICT(id) DO UPDATE SET legal_name=EXCLUDED.legal_name,
                    display_name=EXCLUDED.display_name,verification_status=EXCLUDED.verification_status,
                    management_status=EXCLUDED.management_status,owner_user_id=EXCLUDED.owner_user_id,
                    updated_at=CURRENT_TIMESTAMP
                """, company.companyId(), company.legalName(), company.legalName(),
                SecurityHashes.sha256("BOSS_COMPANY:" + company.companyId()), userId);
        jdbc.update("""
                INSERT INTO boss_company_projections(company_id,tenant_id,legal_name,entity_type,company_status,tenant_status,synchronized_at)
                VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(company_id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,legal_name=EXCLUDED.legal_name,
                    entity_type=EXCLUDED.entity_type,company_status=EXCLUDED.company_status,
                    tenant_status=EXCLUDED.tenant_status,synchronized_at=CURRENT_TIMESTAMP
                """, company.companyId(), company.tenantId(), company.legalName(), company.entityType(),
                company.companyStatus(), company.tenantStatus());
        // Compatibility partition for old business foreign keys only, never a local auth boundary.
        jdbc.update("""
                INSERT INTO workspaces(id,company_id,type,name,owner_user_id,status,created_by,created_at,updated_at)
                VALUES(?,?,'COMPANY',?,?,'ACTIVE',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON CONFLICT(id) DO UPDATE SET name=EXCLUDED.name,updated_at=CURRENT_TIMESTAMP
                """, company.companyId(), company.companyId(), company.legalName(), userId, userId);
        jdbc.update("""
                INSERT INTO boss_legacy_workspace_links(legacy_workspace_id,company_id,tenant_id,migration_source)
                VALUES(?,?,?,'BOSS_RUNTIME_PROJECTION')
                ON CONFLICT(legacy_workspace_id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id
                """, company.companyId(), company.companyId(), company.tenantId());
    }

    private void synchronizePersonalProjection(BossControlPlaneClient.Tenant tenant, UUID userId) {
        jdbc.update("""
                INSERT INTO users(id,phone_hash,phone_last_four,display_name,status,created_at,updated_at)
                VALUES(?,?,'0000',NULL,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON CONFLICT(id) DO UPDATE SET status='ACTIVE',updated_at=CURRENT_TIMESTAMP
                """, userId, SecurityHashes.sha256("BOSS_USER:" + userId));
        // This is only a legacy physical partition row. The identifier is exactly the BOSS Personal
        // Tenant UUID; it grants no local organization, membership or authorization rights.
        jdbc.update("""
                INSERT INTO workspaces(id,company_id,type,name,owner_user_id,status,created_by,created_at,updated_at)
                VALUES(?,NULL,'PERSONAL',?,?,'ACTIVE',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                ON CONFLICT(id) DO UPDATE SET company_id=NULL,type='PERSONAL',name=EXCLUDED.name,updated_at=CURRENT_TIMESTAMP
                """, tenant.tenantId(), tenant.tenantName(), userId, userId);
    }

    public record WorkspaceScope(UUID workspaceId, UUID companyId, String type, String name, String role) { }
}
