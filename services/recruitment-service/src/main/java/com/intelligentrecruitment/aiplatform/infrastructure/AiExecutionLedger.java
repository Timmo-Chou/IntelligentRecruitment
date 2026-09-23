package com.intelligentrecruitment.aiplatform.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Durable IR-side audit/outbox boundary for BOSS usage and cancellation effects. */
@Component
public class AiExecutionLedger {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AiExecutionLedger(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public void authorized(UUID executionId, UUID tenantId, UUID actorId, String businessTaskId, String capability,
                           String idempotencyKey, BossControlPlaneClient.AiAuthorization auth) {
        jdbc.update("INSERT INTO ai_execution_records(id,execution_id,tenant_id,actor_id,business_task_id,authorization_id,reservation_id,grant_id,capability,product_domain,idempotency_key,model_id,tokenizer_id,authorization_expires_at,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'AUTHORIZED') ON CONFLICT (tenant_id,idempotency_key) DO NOTHING",
                UUID.randomUUID(), executionId, tenantId, actorId, businessTaskId, auth.authorizationId(), auth.reservationId(), auth.grantId(), capability,
                "RECRUITMENT", idempotencyKey, auth.modelId(), auth.tokenizerId(), auth.expiresAt() == null ? null : Timestamp.from(auth.expiresAt()));
    }

    @Transactional
    public void agentAccepted(UUID tenantId, String idempotencyKey, String agentTaskId) {
        jdbc.update("UPDATE ai_execution_records SET agent_task_id=?,status='AGENT_ACCEPTED',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND idempotency_key=?", agentTaskId, tenantId, idempotencyKey);
    }

    @Transactional
    public void recordRetryCount(UUID tenantId, String agentTaskId, int retryCount) {
        jdbc.update("UPDATE ai_execution_records SET retry_count=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND agent_task_id=?", Math.max(0, retryCount), tenantId, agentTaskId);
    }

    public int retryCountForAgentTask(UUID tenantId, String agentTaskId) {
        Integer count = jdbc.query("SELECT retry_count FROM ai_execution_records WHERE tenant_id=? AND agent_task_id=?",
                (rs, n) -> rs.getInt(1), tenantId, agentTaskId).stream().findFirst().orElse(0);
        return Math.max(0, count);
    }

    @Transactional
    public void enqueueUsage(String idempotencyKey, BossControlPlaneClient.AiAuthorization auth, String status, String model,
                             int input, int output, int retries, String resultRef) {
        try {
            String payload = json.writeValueAsString(Map.of("authorization_id", auth.authorizationId(), "reservation_id", auth.reservationId(),
                    "tenant_id", auth.tenantId(),
                    "idempotency_key", auth.idempotencyKey(), "task_status", status, "model_id", model,
                    "input_tokens", input, "output_tokens", output, "retry_count", retries, "result_reference", resultRef));
            UUID tenantId = auth.tenantId();
            UUID recordId = jdbc.queryForObject("SELECT id FROM ai_execution_records WHERE tenant_id=? AND idempotency_key=?", UUID.class, tenantId, idempotencyKey);
            jdbc.update("INSERT INTO ai_settlement_outbox(id,execution_id,effect_type,dedupe_key,payload_json) VALUES(?,?, 'USAGE',?,?::jsonb) ON CONFLICT (dedupe_key) DO NOTHING",
                    UUID.randomUUID(), recordId, "usage:" + idempotencyKey, payload);
            jdbc.update("UPDATE ai_execution_records SET status='USAGE_PENDING',model_id=?,retry_count=?,result_reference=?,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND idempotency_key=?", model, retries, resultRef, tenantId, idempotencyKey);
        } catch (Exception ex) { throw new IllegalStateException("AI 用量记录写入失败", ex); }
    }

    @Transactional
    public void enqueueCancellation(BossControlPlaneClient.AiAuthorization auth) {
        try {
            UUID tenantId = auth.tenantId();
            UUID recordId = jdbc.queryForObject("SELECT id FROM ai_execution_records WHERE tenant_id=? AND idempotency_key=?", UUID.class, tenantId, auth.idempotencyKey());
            String payload = json.writeValueAsString(Map.of("authorization_id", auth.authorizationId(), "reservation_id", auth.reservationId(),
                    "tenant_id", auth.tenantId(),
                    "idempotency_key", auth.idempotencyKey()));
            jdbc.update("INSERT INTO ai_settlement_outbox(id,execution_id,effect_type,dedupe_key,payload_json) VALUES(?,?, 'CANCELLATION',?,?::jsonb) ON CONFLICT (dedupe_key) DO NOTHING",
                    UUID.randomUUID(), recordId, "cancellation:" + auth.idempotencyKey(), payload);
            jdbc.update("UPDATE ai_execution_records SET status='CANCELLATION_PENDING',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND idempotency_key=?", tenantId, auth.idempotencyKey());
        } catch (Exception ex) { throw new IllegalStateException("AI 取消记录写入失败", ex); }
    }

    @Transactional
    public Optional<OutboxClaim> claimNext() {
        Instant now = Instant.now();
        return jdbc.query("""
                WITH candidate AS (
                  SELECT id FROM ai_settlement_outbox
                  WHERE (status='PENDING' AND next_attempt_at<=?)
                     OR (status='PROCESSING' AND (locked_until IS NULL OR locked_until<=?))
                  ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE ai_settlement_outbox o SET status='PROCESSING',attempts=attempts+1,
                    next_attempt_at=?,locked_until=?,updated_at=CURRENT_TIMESTAMP
                FROM candidate c WHERE o.id=c.id
                RETURNING o.id,o.execution_id,o.effect_type,o.payload_json,o.attempts
                """, (rs, n) -> new OutboxClaim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getInt(5)), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now.plusSeconds(120)), Timestamp.from(now.plusSeconds(120))).stream().findFirst();
    }

    @Transactional
    public void complete(OutboxClaim claim) {
        jdbc.update("UPDATE ai_settlement_outbox SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?", claim.id());
        jdbc.update("UPDATE ai_execution_records SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                "USAGE".equals(claim.effectType()) ? "SETTLED" : "CANCELLED", claim.executionId());
    }

    @Transactional
    public void retry(OutboxClaim claim, String error) {
        if (claim.attempts() >= 8) {
            jdbc.update("UPDATE ai_settlement_outbox SET status='FAILED',last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", safeError(error), claim.id());
            jdbc.update("UPDATE ai_execution_records SET status='SETTLEMENT_FAILED',updated_at=CURRENT_TIMESTAMP WHERE id=?", claim.executionId());
            return;
        }
        long delay = 1L << Math.min(claim.attempts(), 6);
        jdbc.update("UPDATE ai_settlement_outbox SET status='PENDING',next_attempt_at=?,locked_until=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                Timestamp.from(Instant.now().plusSeconds(delay)), safeError(error), claim.id());
    }

    /** Recreates cancellation metadata after an IR restart when the in-memory map is empty. */
    public Optional<BossControlPlaneClient.AiAuthorization> authorizationForAgentTask(String agentTaskId) {
        return jdbc.query("SELECT authorization_id,grant_id,reservation_id,idempotency_key,model_id,tokenizer_id,tenant_id FROM ai_execution_records WHERE agent_task_id=?",
                (rs,n) -> new BossControlPlaneClient.AiAuthorization(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), null, rs.getString(4), null, rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class)), agentTaskId)
                .stream().findFirst();
    }

    @Transactional
    public void enqueueExpiredCancellations() {
        jdbc.query("SELECT authorization_id,grant_id,reservation_id,idempotency_key,model_id,tokenizer_id,tenant_id FROM ai_execution_records WHERE status IN ('AUTHORIZED','AGENT_ACCEPTED') AND authorization_expires_at IS NOT NULL AND authorization_expires_at<CURRENT_TIMESTAMP",
                (rs, n) -> new BossControlPlaneClient.AiAuthorization(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), null, rs.getString(4), null, rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class)))
                .forEach(this::enqueueCancellation);
    }

    public record OutboxClaim(UUID id, UUID executionId, String effectType, String payloadJson, int attempts) { }

    private static String safeError(String error) {
        if (error == null || error.isBlank()) return "BOSS settlement failed";
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
