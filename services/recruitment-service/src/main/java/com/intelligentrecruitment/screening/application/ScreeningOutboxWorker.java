package com.intelligentrecruitment.screening.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.phase5.worker-enabled", havingValue = "true", matchIfMissing = true)
public class ScreeningOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(ScreeningOutboxWorker.class);

    private final ScreeningService screening;
    public ScreeningOutboxWorker(ScreeningService screening) {
        this.screening = screening;
    }

    @Scheduled(fixedDelayString = "${app.phase5.worker-poll-delay-ms:250}")
    public void poll() {
        ScreeningService.OutboxClaim claim = screening.claimNextRun();
        if (claim != null) {
            try {
                screening.prepareRun(claim.runId());
                screening.completeOutbox(claim.eventId());
            } catch (RuntimeException exception) {
                log.warn("Screening run {} failed on outbox attempt {}", claim.runId(), claim.attempts(), exception);
                screening.failOutbox(claim, exception.getMessage());
            }
        }

        // Process at most one candidate per active run in a poll. This keeps a
        // large batch from monopolising the scheduler and makes cancellation and
        // progress updates responsive.
        for (var runId : screening.runningRunIds()) {
            try {
                screening.processNextItem(runId);
                screening.finalizeRun(runId);
            } catch (RuntimeException exception) {
                log.warn("Screening run {} failed during item processing", runId, exception);
                screening.failRun(runId, exception.getMessage());
            }
        }
    }
}
