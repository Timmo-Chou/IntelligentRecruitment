package com.intelligentrecruitment.recruitment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.phase3.worker-enabled", havingValue = "true", matchIfMissing = true)
public class JdOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(JdOutboxWorker.class);

    private final RecruitmentService recruitment;
    private final long completionDelayMs;
    private final long deltaDelayMs;

    public JdOutboxWorker(RecruitmentService recruitment,
                          @Value("${app.phase3.worker-completion-delay-ms:0}") long completionDelayMs,
                          @Value("${app.phase3.worker-delta-delay-ms:0}") long deltaDelayMs) {
        this.recruitment = recruitment;
        this.completionDelayMs = Math.max(0, completionDelayMs);
        this.deltaDelayMs = Math.max(0, deltaDelayMs);
    }

    @Scheduled(fixedDelayString = "${app.phase3.worker-poll-delay-ms:250}")
    public void poll() {
        RecruitmentService.OutboxClaim claim = recruitment.claimNextJdRun();
        if (claim == null) return;
        try {
            if (recruitment.prepareJdRun(claim.runId())) {
                pause(deltaDelayMs);
                recruitment.emitJdDelta(claim.runId(), 30, "正在理解岗位背景与核心目标…");
                pause(deltaDelayMs);
                recruitment.emitJdDelta(claim.runId(), 50, "正在整理岗位职责、任职要求和关键技能…");
                pause(deltaDelayMs);
                recruitment.emitJdDelta(claim.runId(), 70, "正在生成人才画像并校验结构化字段…");
                pause(completionDelayMs);
                recruitment.finalizeJdRun(claim.runId());
            }
            recruitment.completeJdOutbox(claim.eventId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recruitment.failJdOutbox(claim, "worker interrupted");
        } catch (RuntimeException exception) {
            log.warn("JD run {} failed on outbox attempt {}", claim.runId(), claim.attempts(), exception);
            recruitment.failJdOutbox(claim, exception.getMessage());
        }
    }

    private static void pause(long delayMs) throws InterruptedException {
        if (delayMs > 0) Thread.sleep(delayMs);
    }
}
