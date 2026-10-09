package com.intelligentrecruitment.aiplatform.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Durable IR-side audit/outbox boundary for one explicit terminal BOSS decision. */
@Component
public class AiExecutionLedger {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private com.intelligentrecruitment.candidates.application.PiiCipher pii;
    @Value("${app.ai-platform.reconciliation-hold-seconds:259200}")
    private long reconciliationHoldSeconds;
    @Value("${app.ai-platform.reconciliation-owner:recruitment-operations}") private String reconciliationOwner="recruitment-operations";
    @Value("${app.ai-platform.first-response-seconds:3600}") private long firstResponseSeconds=3600;

    public AiExecutionLedger(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void authorized(UUID executionId, UUID tenantId, UUID actorId, String businessTaskId, String capability,
                           String idempotencyKey, BossControlPlaneClient.AiAuthorization auth) {
        jdbc.update("INSERT INTO ai_execution_records(id,execution_id,tenant_id,actor_id,business_task_id,authorization_id,reservation_id,grant_id,capability,product_domain,idempotency_key,model_id,tokenizer_id,authorization_expires_at,status,agent_id,operation,attempt_id,route_config_version,input_hash,pricing_snapshot,agent_constraints) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'AUTHORIZED',?,?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT (tenant_id,idempotency_key) DO NOTHING",
                UUID.randomUUID(), executionId, tenantId, actorId, businessTaskId, auth.authorizationId(), auth.reservationId(), auth.grantId(), capability,
                "RECRUITMENT", idempotencyKey, auth.modelId(), auth.tokenizerId(), auth.expiresAt() == null ? null : Timestamp.from(auth.expiresAt()),
                auth.agentId(),auth.operation(),auth.attemptId(),auth.routeConfigVersion(),auth.inputHash(),writeJson(auth.pricingSnapshot()),writeJson(auth.agentConstraints()));
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void agentAccepted(UUID tenantId, String idempotencyKey, String agentTaskId) {
        int changed=jdbc.update("UPDATE ai_execution_records SET agent_task_id=?,accepted_at=COALESCE(accepted_at,CURRENT_TIMESTAMP),lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '180 seconds',status='AGENT_ACCEPTED',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND idempotency_key=? AND final_decision IS NULL AND (agent_task_id IS NULL OR agent_task_id=?)", agentTaskId, tenantId, idempotencyKey,agentTaskId);
        if(changed==0){String bound=jdbc.query("SELECT agent_task_id FROM ai_execution_records WHERE tenant_id=? AND idempotency_key=?",(rs,n)->rs.getString(1),tenantId,idempotencyKey).stream().findFirst().orElse(null);if(!agentTaskId.equals(bound))throw new IllegalStateException("Agent任务回执与既有接受绑定冲突");}
    }

    @Transactional
    public void renewAcceptedLease(UUID tenantId,String agentTaskId){
        jdbc.update("UPDATE ai_execution_records SET lease_expires_at=CURRENT_TIMESTAMP + INTERVAL '180 seconds',updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND agent_task_id=? AND final_decision IS NULL AND status IN ('AGENT_ACCEPTED','RUNNING')",tenantId,agentTaskId);
    }

    @Transactional
    public Map<String,Object> decideDeadline(UUID authorizationId,UUID reservationId,String acceptedTask,Instant deadline){
        if(deadline==null||deadline.isAfter(Instant.now()))throw new IllegalArgumentException("Reconciliation deadline is still open");
        List<DeadlineClose> rows=jdbc.query("SELECT tenant_id,idempotency_key FROM ai_execution_records WHERE authorization_id=? AND reservation_id=? FOR UPDATE",(rs,n)->new DeadlineClose(rs.getObject(1,UUID.class),rs.getString(2)),authorizationId,reservationId);
        if(rows.isEmpty())return Map.of("decision_status","NOT_FOUND");
        var row=rows.getFirst();ExecutionRecord record=lockRecord(row.tenantId(),row.idempotencyKey());
        var selected=lockSettlementEffect(record.id());
        if(selected==null){
            if(acceptedTask!=null)jdbc.update("UPDATE ai_execution_records SET agent_task_id=COALESCE(agent_task_id,?),accepted_at=COALESCE(accepted_at,CURRENT_TIMESTAMP) WHERE id=? AND (agent_task_id IS NULL OR agent_task_id=?)",acceptedTask,record.id(),acceptedTask);
            var auth=authorizationForExecution(row.tenantId(),row.idempotencyKey()).orElseThrow();
            String task=jdbc.queryForObject("SELECT agent_task_id FROM ai_execution_records WHERE id=?",String.class,record.id());
            finalizeDecision(auth.idempotencyKey(),auth,"RELEASE","RECONCILIATION_REQUIRED",null,null,null,retryCountForAgentTask(auth.tenantId(),task),task,0,"UNVERIFIED_RESULT","RECONCILIATION_DEADLINE_EXPIRED");
            selected=lockSettlementEffect(record.id());
        }
        return Map.of("decision_status","SELECTED","decision",readJson(selected.payload()));
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void closeObservedDeadlineRelease(BossControlPlaneClient.AiAuthorization auth,String taskId){
        finalizeDecision(auth.idempotencyKey(),auth,"RELEASE","RECONCILIATION_REQUIRED",null,null,null,retryCountForAgentTask(auth.tenantId(),taskId),taskId,0,"UNVERIFIED_RESULT","RECONCILIATION_DEADLINE_EXPIRED");
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public PlatformExecutionStateProjection.Outcome observeTask(UUID tenant,String taskId,long version,String status){
        return PlatformExecutionStateProjection.project(jdbc,tenant,taskId,version,status);
    }

    public boolean cancellationRequested(UUID tenantId,String idempotencyKey){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT cancellation_intent_at IS NOT NULL FROM ai_execution_records WHERE tenant_id=? AND idempotency_key=?",Boolean.class,tenantId,idempotencyKey));}
    @Transactional
    public boolean confirmStandaloneResult(BossControlPlaneClient.AiAuthorization auth,String taskId,com.intelligentrecruitment.agentflow.domain.StructuredResult result){
        ExecutionRecord record=lockRecord(auth.tenantId(),auth.idempotencyKey());
        jdbc.update("UPDATE ai_execution_records SET result_ciphertext=? WHERE id=? AND final_decision IS NULL",pii.encrypt(writeJson(result)),record.id());
        var qualification=ExecutionBillingQualification.evaluate(result,auth.capabilityCode(),auth.operation());
        if(qualification==null)return false;
        String billingMethod=auth.pricingSnapshot()==null?null:auth.pricingSnapshot().path("billing_method").asText(null);
        if("TOKEN".equals(billingMethod)&&result.usage()==null)return false;
        if(!"TOKEN".equals(billingMethod)&&!"FIXED_PER_EXECUTION".equals(billingMethod)&&!"FIXED_PER_CANDIDATE".equals(billingMethod))return false;
        finalizeDecision(auth.idempotencyKey(),auth,qualification.units()>0?"CAPTURE":"RELEASE","COMPLETED",auth.modelId(),
                result.usage()==null?null:(long)result.usage().inputTokens(),result.usage()==null?null:(long)result.usage().outputTokens(),
                retryCountForAgentTask(auth.tenantId(),taskId),taskId,qualification.units(),qualification.validity(),qualification.reason());
        return true;
    }

    @Transactional
    public boolean confirmCancelledExecution(BossControlPlaneClient.AiAuthorization auth,String taskId,com.intelligentrecruitment.agentflow.domain.StructuredResult result){
        ExecutionRecord record=lockRecord(auth.tenantId(),auth.idempotencyKey());
        if(lockSettlementEffect(record.id())!=null)return true;
        var decision=ExecutionBillingQualification.evaluate(result,auth.capabilityCode(),auth.operation());
        jdbc.update("UPDATE ai_execution_records SET cancellation_result_ciphertext=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND final_decision IS NULL",pii.encrypt(writeJson(result)),record.id());
        if(decision==null)return false;
        Long input=result.usage()==null?null:(long)result.usage().inputTokens(),output=result.usage()==null?null:(long)result.usage().outputTokens();
        if("TOKEN".equals(auth.pricingSnapshot().path("billing_method").asText())&&(input==null||output==null))return false;
        finalizeDecision(auth.idempotencyKey(),auth,decision.units()>0?"CAPTURE":"RELEASE","COMPLETED",auth.modelId(),input,output,retryCountForAgentTask(auth.tenantId(),taskId),taskId,decision.units(),decision.validity(),decision.reason());
        return true;
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void submissionUnconfirmed(BossControlPlaneClient.AiAuthorization auth, Instant deadline) {
        jdbc.update("UPDATE ai_execution_records SET status='RECONCILIATION_REQUIRED',reconciliation_deadline=COALESCE(reconciliation_deadline,?),updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND idempotency_key=? AND final_decision IS NULL",
                Timestamp.from(deadline),auth.tenantId(),auth.idempotencyKey());
    }

    public List<PendingExecution> pendingExecutions() {
        return jdbc.query("SELECT tenant_id,idempotency_key,agent_task_id FROM ai_execution_records WHERE final_decision IS NULL AND agent_id IS NOT NULL ORDER BY updated_at LIMIT 100",
                (rs,n)->new PendingExecution(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)));
    }
    public record PendingExecution(UUID tenantId,String idempotencyKey,String agentTaskId) { }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
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
                             Long input, Long output, int retries, String resultRef) {
        enqueueUsage(idempotencyKey, auth, status, model, input, output, retries, resultRef, 1,
                "VERIFIED", "EXECUTION_COMPLETED");
    }

    @Transactional
    public void enqueueUsage(String idempotencyKey, BossControlPlaneClient.AiAuthorization auth, String status, String model,
                             Long input, Long output, int retries, String resultRef, int units, String validity, String reason) {
        String decision = units > 0 ? "CAPTURE" : "RELEASE";
        finalizeDecision(idempotencyKey, auth, decision, status, units > 0 ? model : null,
                units > 0 ? input : null, units > 0 ? output : null, retries, resultRef, units, validity, reason);
    }

    @Transactional
    public void enqueueRelease(BossControlPlaneClient.AiAuthorization auth, String status, String reason, String resultRef) {
        finalizeDecision(auth.idempotencyKey(), auth, "RELEASE", status, null, null, null,
                retryCountForAgentTask(auth.tenantId(), resultRef), resultRef, 0, "CONFIRMED_NO_RESULT", reason);
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void holdForReconciliation(BossControlPlaneClient.AiAuthorization auth,String reason,Instant deadline){
        ExecutionRecord record=lockRecord(auth.tenantId(),auth.idempotencyKey());
        int changed=jdbc.update("UPDATE ai_execution_records SET status='RECONCILIATION_REQUIRED',reconciliation_deadline=COALESCE(reconciliation_deadline,?),lease_expires_at=NULL,billing_reason_code=?,reconciliation_owner=COALESCE(reconciliation_owner,?),reconciliation_started_at=COALESCE(reconciliation_started_at,CURRENT_TIMESTAMP),first_response_due_at=COALESCE(first_response_due_at,CURRENT_TIMESTAMP+(? * INTERVAL '1 second')),updated_at=CURRENT_TIMESTAMP WHERE id=? AND final_decision IS NULL AND (status<>'RECONCILIATION_REQUIRED' OR billing_reason_code IS DISTINCT FROM ?)",
                Timestamp.from(deadline),safeError(reason),reconciliationOwner,Math.max(30,firstResponseSeconds),record.id(),safeError(reason));
        if(changed>0)jdbc.update("INSERT INTO ai_execution_reconciliation_events(id,execution_id,event_type,details) VALUES(?,?, 'RECONCILIATION_HOLD_REQUESTED',jsonb_build_object('reason',?,'deadline',?))",UUID.randomUUID(),record.id(),safeError(reason),deadline.toString());
        if(reason!=null&&(reason.contains("MAPPING")||reason.contains("STRUCTURED_RESULT_MISSING")))
            jdbc.update("UPDATE ai_execution_records SET result_processing_status='PROCESSING_FAILED',result_processing_error_code=? WHERE id=? AND final_decision IS NULL",safeError(reason),record.id());
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void beginResultProcessing(UUID tenantId,String taskId){
        jdbc.update("UPDATE ai_execution_records SET result_processing_status='PROCESSING',result_processing_error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=? AND agent_task_id=? AND final_decision IS NULL AND result_processing_status<>'PROCESSING_SUCCEEDED'",tenantId,taskId);
    }

    @Transactional
    public void finalizeDecision(String idempotencyKey, BossControlPlaneClient.AiAuthorization auth, String decision, String status, String model,
                                 Long input, Long output, int retries, String resultRef) {
        finalizeDecision(idempotencyKey, auth, decision, status, model, input, output, retries, resultRef,
                "CAPTURE".equals(decision) ? 1 : 0,
                "CAPTURE".equals(decision) ? "VERIFIED" : "CONFIRMED_NO_RESULT",
                "CAPTURE".equals(decision) ? "EXECUTION_COMPLETED" : "NO_RESULT_CONFIRMED");
    }

    @Transactional
    public void finalizeDecision(String idempotencyKey, BossControlPlaneClient.AiAuthorization auth, String decision, String status, String model,
                                 Long input, Long output, int retries, String resultRef, int executedUnits, String validity, String reason) {
        try {
            String effect = "CAPTURE".equals(decision) ? "USAGE" : "RELEASE";
            Map<String,Object> payloadData=new java.util.LinkedHashMap<>();
            payloadData.put("authorization_id",auth.authorizationId());payloadData.put("reservation_id",auth.reservationId());
            payloadData.put("tenant_id",auth.tenantId());payloadData.put("idempotency_key",auth.idempotencyKey());
            payloadData.put("final_decision",decision);payloadData.put("task_status",status);payloadData.put("model_id",model);
            payloadData.put("input_tokens",input);payloadData.put("output_tokens",output);payloadData.put("retry_count",retries);
            payloadData.put("result_reference",resultRef);
            payloadData.put("executed_unit_count", executedUnits);
            payloadData.put("unit_validity", validity);
            payloadData.put("billing_reason_code", reason);
            payloadData.put("actor_id",auth.actorId());payloadData.put("agent_id",auth.agentId());payloadData.put("operation",auth.operation());payloadData.put("task_id",auth.taskId());
            payloadData.put("product_domain",auth.productDomain());payloadData.put("capability_code",auth.capabilityCode());
            payloadData.put("attempt_id",auth.attemptId());payloadData.put("route_config_version",auth.routeConfigVersion());
            payloadData.put("input_hash",auth.inputHash());payloadData.put("accepted_task_id",resultRef);
            payloadData.put("pricing_snapshot",auth.pricingSnapshot());payloadData.put("agent_constraints",auth.agentConstraints());
            UUID tenantId = auth.tenantId();
            Map<String,Object> batchSummary=screeningBatchSummary(tenantId,resultRef);
            if(batchSummary!=null)payloadData.put("batch_summary",batchSummary);
            String payload = json.writeValueAsString(payloadData);
            ExecutionRecord record = lockRecord(tenantId, idempotencyKey);
            SettlementEffect existing = lockSettlementEffect(record.id());
            if (existing != null) {
                if (!effect.equals(existing.effectType())) throw new IllegalStateException("同一执行已存在冲突最终决定");
                if (!sameJson(existing.payload(), payload)) throw new IllegalStateException("同一执行的重复最终决定与已保存审计内容冲突");
                // A replay of a successful result must retain its original payload and never
                // create a second final settlement effect.
                return;
            }
            jdbc.update("INSERT INTO ai_settlement_outbox(id,execution_id,effect_type,dedupe_key,payload_json) VALUES(?,?, ?,?,?::jsonb)",
                    UUID.randomUUID(), record.id(), effect, effect.toLowerCase() + ":" + idempotencyKey, payload);
            jdbc.update("UPDATE ai_execution_records SET status=?,final_decision=?,model_id=?,retry_count=?,result_reference=?,result_validity=?,billable_unit_count=?,billing_reason_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    "USAGE".equals(effect) ? "USAGE_PENDING" : "RELEASE_PENDING", decision, model, retries, resultRef, validity,executedUnits,reason, record.id());
            if(validity!=null&&validity.startsWith("VERIFIED"))jdbc.update("UPDATE ai_execution_records SET result_processing_status='PROCESSING_SUCCEEDED',result_processing_error_code=NULL WHERE id=?",record.id());
            if(("RELEASE".equals(decision)||"CAPTURE".equals(decision))&&"RECONCILIATION_DEADLINE_EXPIRED".equals(reason)){
                jdbc.update("""
                        INSERT INTO ai_execution_close_outbox(id,execution_id,closure_id,agent_task_id,tenant_id,attempt_id,agent_id,operation,route_config_version,input_hash,expected_state_version)
                        SELECT gen_random_uuid(),id,id,agent_task_id,tenant_id,attempt_id,agent_id,operation,route_config_version,input_hash,state_version
                        FROM ai_execution_records WHERE id=? AND agent_task_id IS NOT NULL AND attempt_id IS NOT NULL
                          AND agent_id IS NOT NULL AND operation IS NOT NULL AND route_config_version IS NOT NULL AND input_hash IS NOT NULL
                        ON CONFLICT (execution_id) DO NOTHING
                        """,record.id());
            }
        } catch (Exception ex) { throw new IllegalStateException("AI 用量记录写入失败", ex); }
    }

    @Transactional
    public void enqueueCancellation(BossControlPlaneClient.AiAuthorization auth) {
        try {
            UUID tenantId = auth.tenantId();
            ExecutionRecord record = lockRecord(tenantId, auth.idempotencyKey());
            jdbc.update("UPDATE ai_execution_records SET cancellation_intent_at=COALESCE(cancellation_intent_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id=?",record.id());
            SettlementEffect existing = lockSettlementEffect(record.id());
            if (existing != null) {
                // Once usage is queued it is the selected final effect.  A late task
                // cancellation is obsolete; after completion it must not rewrite IR's
                // ledger to CANCELLED or send a second BOSS effect.
                if ("USAGE".equals(existing.effectType())) return;
                return;
            }
            jdbc.update("UPDATE ai_execution_records SET cancellation_intent_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?", record.id());
            // Cancellation is intent only. A RELEASE is decided after the platform confirms
            // that no work was accepted or the execution coordinator closes reconciliation.
            jdbc.update("UPDATE ai_execution_records SET cancellation_intent_at=COALESCE(cancellation_intent_at,CURRENT_TIMESTAMP),status='CANCEL_REQUESTED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND final_decision IS NULL", record.id());
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
                "USAGE".equals(claim.effectType()) ? "SETTLED" : "RELEASED", claim.executionId());
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
        return jdbc.query("SELECT authorization_id,grant_id,reservation_id,idempotency_key,model_id,tokenizer_id,tenant_id,business_task_id,product_domain,capability,agent_id,operation,attempt_id,route_config_version,input_hash,pricing_snapshot,agent_constraints,actor_id FROM ai_execution_records WHERE agent_task_id=?",
                (rs,n) -> new BossControlPlaneClient.AiAuthorization(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), null, rs.getString(4), null, rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class),
                        rs.getObject(18,UUID.class),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getObject(13,UUID.class),rs.getString(14),rs.getString(15),
                        readJson(rs.getString(16)),readJson(rs.getString(17))), agentTaskId)
                .stream().findFirst();
    }

    public Optional<BossControlPlaneClient.AiAuthorization> authorizationForExecution(UUID tenantId,String idempotencyKey){
        return jdbc.query("SELECT authorization_id,grant_id,reservation_id,idempotency_key,model_id,tokenizer_id,tenant_id,business_task_id,product_domain,capability,agent_id,operation,attempt_id,route_config_version,input_hash,pricing_snapshot,agent_constraints,actor_id FROM ai_execution_records WHERE tenant_id=? AND idempotency_key=?",
                (rs,n)->new BossControlPlaneClient.AiAuthorization(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),null,rs.getString(4),null,rs.getString(5),rs.getString(6),rs.getObject(7,UUID.class),rs.getObject(18,UUID.class),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getObject(13,UUID.class),rs.getString(14),rs.getString(15),readJson(rs.getString(16)),readJson(rs.getString(17))),tenantId,idempotencyKey).stream().findFirst();
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String value){try{return value==null?null:json.readTree(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String writeJson(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("授权快照序列化失败",e);}}

    @Transactional
    public void enqueueExpiredCancellations() {
        jdbc.update("WITH expired AS (UPDATE ai_execution_records SET status='RECONCILIATION_REQUIRED',reconciliation_deadline=COALESCE(reconciliation_deadline,CURRENT_TIMESTAMP + (? * INTERVAL '1 second')),lease_expires_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE status IN ('AUTHORIZED','AGENT_ACCEPTED','RUNNING') AND authorization_expires_at IS NOT NULL AND authorization_expires_at<CURRENT_TIMESTAMP AND final_decision IS NULL AND (lease_expires_at IS NULL OR lease_expires_at<=CURRENT_TIMESTAMP) RETURNING id) INSERT INTO ai_execution_reconciliation_events(id,execution_id,event_type,details) SELECT gen_random_uuid(),id,'GRANT_EXPIRED_ACCEPTED_TASK',jsonb_build_object('source','grant_expiry_scan') FROM expired",Math.max(60,reconciliationHoldSeconds));
        List<DeadlineClose> expired=jdbc.query("SELECT tenant_id,idempotency_key FROM ai_execution_records WHERE status='RECONCILIATION_REQUIRED' AND reconciliation_deadline<=CURRENT_TIMESTAMP AND final_decision IS NULL ORDER BY reconciliation_deadline FOR UPDATE SKIP LOCKED LIMIT 100",
                (rs,n)->new DeadlineClose(rs.getObject(1,UUID.class),rs.getString(2)));
        for(DeadlineClose close:expired){
            BossControlPlaneClient.AiAuthorization auth=authorizationForExecution(close.tenantId(),close.idempotencyKey()).orElse(null);
            if(auth==null)continue;
            String accepted=jdbc.query("SELECT agent_task_id FROM ai_execution_records WHERE tenant_id=? AND idempotency_key=?",(rs,n)->rs.getString(1),close.tenantId(),close.idempotencyKey()).stream().findFirst().orElse(null);
            BatchDeadlineClose batch=accepted==null?null:closeExpiredScreeningBatch(close.tenantId(),accepted);
            if(accepted!=null&&batch==null&&isScreeningBatch(close.tenantId(),accepted))batch=new BatchDeadlineClose(false,0,0);
            if(batch!=null&&!batch.safe()){
                jdbc.update("INSERT INTO ai_execution_reconciliation_events(id,execution_id,event_type,details) SELECT gen_random_uuid(),id,'BATCH_DEADLINE_CLOSE_BLOCKED',jsonb_build_object('reason','BATCH_EVIDENCE_INCONSISTENT') FROM ai_execution_records WHERE tenant_id=? AND idempotency_key=?",close.tenantId(),close.idempotencyKey());
                continue;
            }
            int units=batch==null?0:batch.verifiedUnits();
            boolean unknownClosed=batch!=null&&batch.unknownUnits()>0;
            String closeReason=batch==null||unknownClosed?"RECONCILIATION_DEADLINE_EXPIRED":units>0?"RD_MATCH_BATCH_RECOVERED":"RD_MATCH_BATCH_CONFIRMED_NO_RESULT";
            String validity=units>0?"VERIFIED_VALID_RESULT":unknownClosed?"UNVERIFIED_RESULT":"CONFIRMED_NO_RESULT";
            finalizeDecision(close.idempotencyKey(),auth,units>0?"CAPTURE":"RELEASE","RECONCILIATION_REQUIRED",units>0?auth.modelId():null,null,null,
                    retryCountForAgentTask(close.tenantId(),accepted),accepted,units,validity,closeReason);
            jdbc.update("INSERT INTO ai_execution_reconciliation_events(id,execution_id,event_type,details) SELECT gen_random_uuid(),id,'RECONCILIATION_DEADLINE_CLOSED',jsonb_build_object('reason','OUTCOME_UNVERIFIABLE') FROM ai_execution_records WHERE tenant_id=? AND idempotency_key=?",
                    close.tenantId(),close.idempotencyKey());
        }
    }

    public record OutboxClaim(UUID id, UUID executionId, String effectType, String payloadJson, int attempts) { }

    private BatchDeadlineClose closeExpiredScreeningBatch(UUID tenantId,String taskId){
        List<ScreeningBatch> batches=jdbc.query("SELECT id,candidate_count FROM screening_execution_batches WHERE tenant_id=? AND provider_task_id=? FOR UPDATE",
                (rs,n)->new ScreeningBatch(rs.getObject(1,UUID.class),rs.getInt(2)),tenantId,taskId);
        if(batches.isEmpty())return null;
        ScreeningBatch batch=batches.getFirst();
        List<BatchItem> items=jdbc.query("SELECT id,batch_order,status,result_validity,billable_unit_count FROM screening_run_items WHERE tenant_id=? AND batch_id=? ORDER BY batch_order FOR UPDATE",
                (rs,n)->new BatchItem(rs.getObject(1,UUID.class),rs.getInt(2),rs.getString(3),rs.getString(4),(Integer)rs.getObject(5)),tenantId,batch.id());
        if(batch.candidateCount()<1||batch.candidateCount()>5||items.size()!=batch.candidateCount())return new BatchDeadlineClose(false,0,0);
        int verifiedUnits=0;List<UUID> unknown=new java.util.ArrayList<>();
        for(int i=0;i<items.size();i++){
            BatchItem item=items.get(i);if(item.order()!=i+1)return new BatchDeadlineClose(false,0,0);
            if("VERIFIED_VALID_RESULT".equals(item.validity())&&item.units()!=null&&item.units()==1){verifiedUnits++;continue;}
            if("CONFIRMED_NO_RESULT".equals(item.validity())&&Integer.valueOf(0).equals(item.units()))continue;
            if((item.validity()==null||"UNVERIFIED_RESULT".equals(item.validity()))
                    &&java.util.Set.of("PROCESSING","RECONCILIATION_REQUIRED","CANCELLED").contains(item.status())
                    &&(item.units()==null||item.units()==0)){unknown.add(item.id());continue;}
            return new BatchDeadlineClose(false,0,0);
        }
        for(UUID itemId:unknown)jdbc.update("UPDATE screening_run_items SET status=CASE WHEN status='CANCELLED' THEN status ELSE 'FAILED' END,error_code=CASE WHEN status='CANCELLED' THEN error_code ELSE 'OUTCOME_UNVERIFIABLE' END,result_validity='UNVERIFIED_RESULT',billable_unit_count=0,billing_reason_code='RECONCILIATION_DEADLINE_EXPIRED',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PROCESSING','RECONCILIATION_REQUIRED','CANCELLED')",itemId);
        jdbc.update("UPDATE screening_execution_batches SET status='CLOSED_DEADLINE',updated_at=CURRENT_TIMESTAMP WHERE id=?",batch.id());
        return new BatchDeadlineClose(true,verifiedUnits,unknown.size());
    }

    private Map<String,Object> screeningBatchSummary(UUID tenantId,String taskId){
        if(taskId==null)return null;
        List<Map<String,Object>> rows=jdbc.query("""
                SELECT b.id,b.candidate_count,b.status,COUNT(i.id) AS persisted_count,
                  COALESCE(SUM(CASE WHEN i.result_validity='VERIFIED_VALID_RESULT' THEN i.billable_unit_count ELSE 0 END),0) AS billable_unit_count,
                  COUNT(*) FILTER(WHERE i.result_validity IN ('CONFIRMED_NO_RESULT','VERIFIED_NO_BILLABLE_RESULT')) AS confirmed_no_result_count,
                  COUNT(*) FILTER(WHERE i.billing_reason_code='RECONCILIATION_DEADLINE_EXPIRED') AS deadline_closed_unknown_count,
                  COUNT(*) FILTER(WHERE i.status IN ('PENDING','PROCESSING','RECONCILIATION_REQUIRED') OR i.billable_unit_count IS NULL) AS unresolved_count
                FROM screening_execution_batches b JOIN screening_run_items i ON i.batch_id=b.id
                WHERE b.tenant_id=? AND b.provider_task_id=? GROUP BY b.id,b.candidate_count,b.status
                """,(rs,n)->Map.of("batch_id",rs.getObject(1,UUID.class).toString(),"candidate_count",rs.getInt(2),"status",rs.getString(3),
                "persisted_count",rs.getInt(4),"billable_unit_count",rs.getInt(5),"confirmed_no_result_count",rs.getInt(6),
                "deadline_closed_unknown_count",rs.getInt(7),"unresolved_count",rs.getInt(8)),tenantId,taskId);
        return rows.stream().findFirst().orElse(null);
    }

    private boolean isScreeningBatch(UUID tenantId,String taskId){
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM screening_execution_batches WHERE tenant_id=? AND provider_task_id=?)",Boolean.class,tenantId,taskId));
    }

    private record ScreeningBatch(UUID id,int candidateCount){}
    private record BatchItem(UUID id,int order,String status,String validity,Integer units){}
    private record BatchDeadlineClose(boolean safe,int verifiedUnits,int unknownUnits){}

    /** Locks one IR execution before choosing its single terminal BOSS effect. */
    private ExecutionRecord lockRecord(UUID tenantId, String idempotencyKey) {
        return jdbc.query("SELECT id FROM ai_execution_records WHERE tenant_id=? AND idempotency_key=? FOR UPDATE",
                        (rs, n) -> new ExecutionRecord(rs.getObject(1, UUID.class)), tenantId, idempotencyKey)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 AI 执行台账"));
    }

    /** The V4 unique constraint is the database backstop for this application-level decision. */
    private SettlementEffect lockSettlementEffect(UUID executionRecordId) {
        return jdbc.query("SELECT effect_type,status,payload_json::text FROM ai_settlement_outbox WHERE execution_id=? FOR UPDATE",
                        (rs, n) -> new SettlementEffect(rs.getString(1), rs.getString(2), rs.getString(3)), executionRecordId)
                .stream().findFirst().orElse(null);
    }

    private boolean sameJson(String left, String right) {
        try { return json.readTree(left).equals(json.readTree(right)); }
        catch (Exception ex) { throw new IllegalStateException("最终决定审计内容无法读取", ex); }
    }

    private record ExecutionRecord(UUID id) { }
    private record DeadlineClose(UUID tenantId,String idempotencyKey) { }
    private record SettlementEffect(String effectType, String status, String payload) { }

    private static String safeError(String error) {
        if (error == null || error.isBlank()) return "BOSS settlement failed";
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }
}
