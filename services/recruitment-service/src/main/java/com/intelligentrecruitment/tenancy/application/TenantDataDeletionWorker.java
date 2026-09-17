package com.intelligentrecruitment.tenancy.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.aiplatform.infrastructure.HttpAiPlatformClient;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Executes BOSS-owned 180-day deletion tasks with retryable, idempotent logical deletes. */
@Component
@ConditionalOnProperty(name = "app.tenant-data-deletion-worker-enabled", havingValue = "true", matchIfMissing = true)
public class TenantDataDeletionWorker {
    private static final Logger log = LoggerFactory.getLogger(TenantDataDeletionWorker.class);
    private final BossControlPlaneClient boss;
    private final JdbcTemplate jdbc;
    private final HttpAiPlatformClient ai;
    public TenantDataDeletionWorker(BossControlPlaneClient boss, JdbcTemplate jdbc, HttpAiPlatformClient ai) { this.boss = boss; this.jdbc = jdbc; this.ai = ai; }

    @Scheduled(fixedDelayString = "${app.tenant-data-deletion-worker-poll-delay-ms:1000}")
    public void poll() {
        JsonNode body;
        try { body = boss.claimRecruitmentDataDeletion(); } catch (RuntimeException e) { log.warn("claim tenant deletion task failed", e); return; }
        JsonNode task = body.path("task");
        if (task.isMissingNode() || task.isNull() || !task.hasNonNull("id")) return;
        UUID taskId = UUID.fromString(task.path("id").asText());
        UUID tenantId = UUID.fromString(task.path("tenant_id").asText());
        try {
            logicallyDelete(tenantId);
            boss.completeRecruitmentDataDeletion(taskId);
        } catch (RuntimeException e) {
            log.warn("tenant {} logical deletion failed", tenantId, e);
            try { boss.failRecruitmentDataDeletion(taskId, e.getMessage()); } catch (RuntimeException callback) { log.warn("deletion failure callback failed", callback); }
        }
    }

    @Transactional
    void logicallyDelete(UUID tenantId) {
        ai.logicallyDeleteTenantBusinessData(tenantId);
        jdbc.update("UPDATE candidates SET status='DELETED',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND status<>'DELETED'", tenantId);
        jdbc.update("UPDATE jobs SET status='ARCHIVED',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND status<>'ARCHIVED'", tenantId);
        jdbc.update("UPDATE resume_files SET status='DELETED',error_code='TENANT_DATA_DELETED',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=?", tenantId);
        jdbc.update("UPDATE file_assets SET lifecycle_status='DELETED' WHERE tenant_id=? AND lifecycle_status<>'DELETED'", tenantId);
        jdbc.update("UPDATE enterprise_pool_attachment_assets SET lifecycle_status='DELETED',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=?", tenantId);
        jdbc.update("UPDATE enterprise_talent_pool_copies SET lifecycle_status='DELETED',deleted_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND lifecycle_status<>'DELETED'", tenantId);
        jdbc.update("UPDATE enterprise_job_pool_copies SET lifecycle_status='DELETED',deleted_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND lifecycle_status<>'DELETED'", tenantId);
    }
}
