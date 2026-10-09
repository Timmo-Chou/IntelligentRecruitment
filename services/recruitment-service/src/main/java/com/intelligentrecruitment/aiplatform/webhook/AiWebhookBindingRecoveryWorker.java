package com.intelligentrecruitment.aiplatform.webhook;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries event projection after the matching IR acceptance ledger becomes durable. */
@Component
public class AiWebhookBindingRecoveryWorker {
    private final AiWebhookService service;
    public AiWebhookBindingRecoveryWorker(AiWebhookService service){this.service=service;}
    @Scheduled(fixedDelayString="${app.ai-platform.webhook.binding-recovery-delay-ms:3000}")
    public void applyPending(){service.applyEventsAwaitingBinding();}
}
