package com.intelligentrecruitment.recruitment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Starts and finalizes BOSS-authorized interview-kit tasks through the durable outbox. */
@Component
@ConditionalOnProperty(name = "app.phase4.interview-kit-worker-enabled", havingValue = "true", matchIfMissing = true)
public class InterviewKitOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(InterviewKitOutboxWorker.class);
    private final RecruitmentService recruitment;

    public InterviewKitOutboxWorker(RecruitmentService recruitment) {
        this.recruitment = recruitment;
    }

    @Scheduled(fixedDelayString = "${app.phase4.interview-kit-worker-poll-delay-ms:250}")
    public void poll() {
        RecruitmentService.OutboxClaim claim = recruitment.claimNextInterviewKitRun();
        if (claim != null) {
            try {
                recruitment.prepareInterviewKitRun(claim.runId());
                recruitment.completeInterviewKitOutbox(claim.eventId());
            } catch (RuntimeException exception) {
                log.warn("Interview-kit run {} failed to start on outbox attempt {}", claim.runId(), claim.attempts(), exception);
                recruitment.failInterviewKitOutbox(claim, exception.getMessage());
            }
        }
        for (UUID runId : recruitment.runningInterviewKitRunIds()) {
            try {
                recruitment.finalizeInterviewKitRunIfReady(runId);
            } catch (RuntimeException exception) {
                log.warn("Interview-kit run {} finalization check failed", runId, exception);
            }
        }
    }
}
