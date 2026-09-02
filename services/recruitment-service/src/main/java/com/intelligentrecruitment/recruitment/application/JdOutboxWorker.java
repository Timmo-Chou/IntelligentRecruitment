package com.intelligentrecruitment.recruitment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.phase3.worker-enabled", havingValue = "true", matchIfMissing = true)
public class JdOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(JdOutboxWorker.class);

    private final RecruitmentService recruitment;
    public JdOutboxWorker(RecruitmentService recruitment) {
        this.recruitment = recruitment;
    }

    @Scheduled(fixedDelayString = "${app.phase3.worker-poll-delay-ms:250}")
    public void poll() {
        RecruitmentService.OutboxClaim claim = recruitment.claimNextJdRun();
        if (claim != null) {
            try {
                recruitment.prepareJdRun(claim.runId());
                recruitment.completeJdOutbox(claim.eventId());
            } catch (RuntimeException exception) {
                log.warn("JD run {} failed to start on outbox attempt {}", claim.runId(), claim.attempts(), exception);
                recruitment.failJdOutbox(claim, exception.getMessage());
            }
        }
        for (java.util.UUID runId : recruitment.runningJdRunIds()) {
            try {
                recruitment.finalizeJdRunIfReady(runId);
            } catch (RuntimeException exception) {
                log.warn("JD run {} finalization check failed", runId, exception);
            }
        }
    }
}
