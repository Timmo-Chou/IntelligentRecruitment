package com.intelligentrecruitment.aiplatform.webhook;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Inbound AIAgentPlatform task-status callback endpoint. */
@RestController
@RequestMapping("/internal/v1/ai/webhooks")
public class AiWebhookController {
    private final AiWebhookService webhooks;

    public AiWebhookController(AiWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/task-status")
    ResponseEntity<Map<String, Object>> taskStatus(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestBody String body) {
        AiWebhookService.ReceiveResult result = webhooks.receive(signature, timestamp, body);
        return ResponseEntity.accepted().body(Map.of(
                "accepted", result.accepted(),
                "duplicate_or_stale", result.duplicateOrStale()));
    }
}
