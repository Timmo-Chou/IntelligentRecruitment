package com.intelligentrecruitment.recruitment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AI 简历解析 outbox 异步 worker。
 * 逻辑与 JD 生成 worker 双轨独立：
 *   1) 每 250ms claim 下一条 RESUME_PARSE_RUN_REQUESTED 事件；
 *   2) prepareResumeParseRun：把 QUEUED→RUNNING，调用 AiPlatform.startTask 开始真实/模拟推理；
 *   3) 循环 runningResumeParseRunIds() → finalizeResumeParseRunIfReady：provider 一旦 COMPLETED 即写入 resume_parse_drafts 新 revision 并结算；
 *   4) 失败 3 次以内重试，超过标记 FAILED 退回费用。
 */
@Component
@ConditionalOnProperty(name = "app.phase4.resume-parse-worker-enabled", havingValue = "true", matchIfMissing = true)
public class ResumeParseOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(ResumeParseOutboxWorker.class);

    private final RecruitmentService recruitment;

    public ResumeParseOutboxWorker(RecruitmentService recruitment) {
        this.recruitment = recruitment;
    }

    @Scheduled(fixedDelayString = "${app.phase4.resume-parse-worker-poll-delay-ms:250}")
    public void poll() {
        // 1) 取未处理 outbox：调用 AiPlatform 启动 task
        RecruitmentService.OutboxClaim claim = recruitment.claimNextResumeParseRun();
        if (claim != null) {
            try {
                recruitment.prepareResumeParseRun(claim.runId());
                recruitment.completeResumeParseOutbox(claim.eventId());
            } catch (RuntimeException exception) {
                log.warn("Resume parse run {} failed to start on outbox attempt {}", claim.runId(), claim.attempts(), exception);
                recruitment.failResumeParseOutbox(claim, exception.getMessage());
            }
        }
        // 2) 轮询正在 RUNNING 的 ai_runs：provider 完成后自动落盘 resume_parse_drafts + 结算
        for (UUID runId : recruitment.runningResumeParseRunIds()) {
            try {
                recruitment.finalizeResumeParseRunIfReady(runId);
            } catch (RuntimeException exception) {
                log.warn("Resume parse run {} finalization check failed", runId, exception);
            }
        }
    }
}
