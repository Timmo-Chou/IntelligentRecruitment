package com.intelligentrecruitment.candidates.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives direct candidate uploads through the same BOSS-authorized asynchronous AI task flow. */
@Component
@ConditionalOnProperty(name = "app.candidate-resume-parse-worker-enabled", havingValue = "true", matchIfMissing = true)
public class CandidateResumeParseOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(CandidateResumeParseOutboxWorker.class);
    private final CandidateService candidates;

    public CandidateResumeParseOutboxWorker(CandidateService candidates) { this.candidates = candidates; }

    @Scheduled(fixedDelayString = "${app.candidate-resume-parse-worker-poll-delay-ms:250}")
    public void poll() {
        CandidateService.ResumeParseOutboxClaim claim = candidates.claimNextResumeParse();
        if (claim != null) {
            try {
                candidates.startResumeParse(claim.resumeFileId());
                candidates.completeResumeParseOutbox(claim.eventId());
            } catch (RuntimeException exception) {
                log.warn("Candidate resume parse {} failed to start on attempt {}", claim.resumeFileId(), claim.attempts(), exception);
                candidates.failResumeParseOutbox(claim, exception.getMessage());
            }
        }
        for (UUID resumeFileId : candidates.runningResumeParseIds()) {
            try {
                candidates.finalizeResumeParseIfReady(resumeFileId);
            } catch (RuntimeException exception) {
                log.warn("Candidate resume parse {} finalization failed", resumeFileId, exception);
            }
        }
    }
}
