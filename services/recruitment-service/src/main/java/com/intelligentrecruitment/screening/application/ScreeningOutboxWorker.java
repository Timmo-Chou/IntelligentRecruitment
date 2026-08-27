package com.intelligentrecruitment.screening.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.phase5.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ScreeningOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(ScreeningOutboxWorker.class);

    private final ScreeningService screening;
    private final long itemDelayMs;

    public ScreeningOutboxWorker(ScreeningService screening,
                                 @Value("${app.phase5.worker-item-delay-ms:0}") long itemDelayMs) {
        this.screening = screening;
        this.itemDelayMs = Math.max(0, itemDelayMs);
    }

    @Scheduled(fixedDelayString = "${app.phase5.worker-poll-delay-ms:250}")
    public void poll() {
        ScreeningService.OutboxClaim claim = screening.claimNextRun();
        if (claim == null) return;
        try {
            if (screening.prepareRun(claim.runId())) {
                while (screening.processNextItem(claim.runId())) pauseBetweenItems();
                screening.finalizeRun(claim.runId());
            }
            screening.completeOutbox(claim.eventId());
        } catch (RuntimeException exception) {
            log.warn("Screening run {} failed on outbox attempt {}", claim.runId(), claim.attempts(), exception);
            screening.failOutbox(claim, exception.getMessage());
        }
    }

    private void pauseBetweenItems() {
        if (itemDelayMs == 0) return;
        try {
            Thread.sleep(itemDelayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("screening worker interrupted", exception);
        }
    }
}
