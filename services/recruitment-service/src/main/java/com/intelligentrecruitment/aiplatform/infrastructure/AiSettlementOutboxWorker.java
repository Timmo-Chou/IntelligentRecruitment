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
                    UUID.fromString(body.path("tenant_id").asText()));
            if ("USAGE".equals(claim.effectType())) {
                boss.reportAiUsage(auth, body.path("task_status").asText(), body.path("model_id").asText(),
                        body.path("input_tokens").asInt(), body.path("output_tokens").asInt(),
                        body.path("retry_count").asInt(), body.path("result_reference").asText());
            } else if ("CANCELLATION".equals(claim.effectType())) {
                boss.cancelAiExecution(auth);
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
}
