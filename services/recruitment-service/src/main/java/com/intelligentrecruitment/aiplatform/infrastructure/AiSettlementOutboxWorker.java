package com.intelligentrecruitment.aiplatform.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers durable IR settlement effects to BOSS with retry and restart recovery. */
@Component
public class AiSettlementOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(AiSettlementOutboxWorker.class);
    private final AiExecutionLedger ledger;
    private final BossControlPlaneClient boss;
    private final ObjectMapper json;

    public AiSettlementOutboxWorker(AiExecutionLedger ledger, BossControlPlaneClient boss, ObjectMapper json) {
        this.ledger = ledger;
        this.boss = boss;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${app.ai-platform.settlement.poll-delay-ms:500}")
    public void processOne() {
        Optional<AiExecutionLedger.OutboxClaim> claimed = ledger.claimNext();
        if (claimed.isEmpty()) return;
        AiExecutionLedger.OutboxClaim claim = claimed.get();
        try {
            JsonNode body = json.readTree(claim.payloadJson());
            BossControlPlaneClient.AiAuthorization auth = new BossControlPlaneClient.AiAuthorization(
                    UUID.fromString(body.path("authorization_id").asText()),
                    null,
                    UUID.fromString(body.path("reservation_id").asText()),
                    null,
                    body.path("idempotency_key").asText(), null,
                    body.path("model_id").asText(null), null,
                    UUID.fromString(body.path("tenant_id").asText()),
                    body.hasNonNull("actor_id")?UUID.fromString(body.path("actor_id").asText()):null,
                    body.path("task_id").asText(null),body.path("product_domain").asText(null),body.path("capability_code").asText(null),
                    body.path("agent_id").asText(null),body.path("operation").asText(null),
                    body.hasNonNull("attempt_id")?UUID.fromString(body.path("attempt_id").asText()):null,
                    body.path("route_config_version").asText(null),body.path("input_hash").asText(null),
                    body.path("pricing_snapshot"),body.path("agent_constraints"));
            if ("USAGE".equals(claim.effectType())) {
                if(body.hasNonNull("agent_id"))boss.settleAiExecutionV2(auth,body.path("accepted_task_id").asText(null),"CAPTURE",
                        body.path("executed_unit_count").asInt(1),body.path("unit_validity").asText("VERIFIED"),body.path("billing_reason_code").asText("EXECUTION_COMPLETED"),
                        body.path("task_status").asText(),body.path("model_id").asText(null),nullableLong(body,"input_tokens"),nullableLong(body,"output_tokens"),
                        body.path("retry_count").asInt(),body.path("result_reference").asText(null),body.hasNonNull("batch_summary")?body.path("batch_summary"):null);
                else boss.reportAiUsage(auth, body.path("task_status").asText(), body.path("model_id").asText(),
                        nullableLong(body,"input_tokens"), nullableLong(body,"output_tokens"), body.path("retry_count").asInt(), body.path("result_reference").asText(null));
            } else if ("RELEASE".equals(claim.effectType())) {
                if(body.hasNonNull("agent_id"))boss.settleAiExecutionV2(auth,body.path("accepted_task_id").asText(null),"RELEASE",0,
                        body.path("unit_validity").asText("CONFIRMED_NO_RESULT"),body.path("billing_reason_code").asText("NO_RESULT_CONFIRMED"),
                        body.path("task_status").asText("FAILED"),body.path("model_id").asText(null),nullableLong(body,"input_tokens"),nullableLong(body,"output_tokens"),
                        body.path("retry_count").asInt(),body.path("result_reference").asText(null),body.hasNonNull("batch_summary")?body.path("batch_summary"):null);
                else boss.cancelAiExecution(auth);
            } else {
                throw new IllegalStateException("未知 AI settlement effect: " + claim.effectType());
            }
            ledger.complete(claim);
        } catch (Exception ex) {
            log.warn("AI settlement outbox delivery failed id={} type={} attempt={}", claim.id(), claim.effectType(), claim.attempts(), ex);
            ledger.retry(claim, ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.ai-platform.settlement.reconcile-delay-ms:60000}")
    public void reconcileExpiredReservations() {
        try {
            ledger.enqueueExpiredCancellations();
        } catch (RuntimeException exception) {
            log.warn("AI reservation expiry reconciliation failed", exception);
        }
    }

    private static Long nullableLong(JsonNode body,String field) {
        JsonNode value=body.get(field);
        return value==null||value.isNull()?null:value.longValue();
    }
}
