package com.intelligentrecruitment.recruitment.api;

import com.intelligentrecruitment.jobs.application.JobService;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.recruitment.application.RecruitmentService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/recruitment-tasks")
public class RecruitmentController {

    private final RecruitmentService recruitment;

    public RecruitmentController(RecruitmentService recruitment) {
        this.recruitment = recruitment;
    }

    @PostMapping
    RecruitmentService.TaskDetail create(@PathVariable UUID workspaceId,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @RequestBody RecruitmentService.CreateTaskInput input,
                                         Authentication authentication) {
        return recruitment.createTask(CurrentUser.id(authentication), workspaceId, idempotencyKey, input);
    }

    @GetMapping
    List<RecruitmentService.TaskSummary> list(@PathVariable UUID workspaceId, Authentication authentication) {
        return recruitment.listTasks(CurrentUser.id(authentication), workspaceId);
    }

    @GetMapping("/{taskId}")
    RecruitmentService.TaskDetail get(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                      Authentication authentication) {
        return recruitment.getTask(CurrentUser.id(authentication), workspaceId, taskId);
    }

    @PutMapping("/{taskId}")
    RecruitmentService.TaskSummary rename(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                          @RequestBody RecruitmentService.RenameTaskInput input,
                                          Authentication authentication) {
        return recruitment.renameTask(CurrentUser.id(authentication), workspaceId, taskId, input);
    }

    @DeleteMapping("/{taskId}")
    void delete(@PathVariable UUID workspaceId, @PathVariable UUID taskId, Authentication authentication) {
        recruitment.deleteTask(CurrentUser.id(authentication), workspaceId, taskId);
    }

    @PostMapping("/{taskId}/messages")
    RecruitmentService.TaskDetail message(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                          @RequestBody RecruitmentService.MessageInput input,
                                          Authentication authentication) {
        return recruitment.addMessage(CurrentUser.id(authentication), workspaceId, taskId, input);
    }

    @PostMapping("/{taskId}/agent-routes")
    RouteDecision route(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                        @RequestBody RecruitmentService.RouteMessageInput input,
                        Authentication authentication) {
        return recruitment.routeMessage(CurrentUser.id(authentication), workspaceId, taskId, input);
    }

    @PostMapping(value = "/{taskId}/jd-source-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    RecruitmentService.SourceFileView uploadJdSourceFile(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                                         @RequestPart("file") MultipartFile file,
                                                         Authentication authentication) {
        return recruitment.uploadJdSourceFile(CurrentUser.id(authentication), workspaceId, taskId, file);
    }

    @PostMapping(value = "/{taskId}/resume-source-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    RecruitmentService.SourceFileView uploadResumeSourceFile(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                                             @RequestPart("file") MultipartFile file,
                                                             Authentication authentication) {
        return recruitment.uploadResumeSourceFile(CurrentUser.id(authentication), workspaceId, taskId, file);
    }

    @PostMapping("/{taskId}/jd-runs")
    RecruitmentService.TaskDetail generate(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @RequestBody RecruitmentService.GenerateJdInput input,
                                           Authentication authentication) {
        return recruitment.generateJd(CurrentUser.id(authentication), workspaceId, taskId, idempotencyKey, input);
    }

    @GetMapping(value = "/{taskId}/jd-runs/events", produces = "text/event-stream")
    StreamingResponseBody events(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                 @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                 Authentication authentication, HttpServletResponse response) {
        UUID userId = CurrentUser.id(authentication);
        long initialCursor = parseCursor(lastEventId);
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return output -> {
            long cursor = initialCursor;
            long deadline = System.currentTimeMillis() + 55_000;
            while (System.currentTimeMillis() < deadline) {
                List<RecruitmentService.RunEvent> batch = recruitment.runEvents(userId, workspaceId, taskId, cursor);
                if (batch.isEmpty()) {
                    output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    sleep();
                    continue;
                }
                for (RecruitmentService.RunEvent event : batch) {
                    String frame = "id: " + event.eventId() + "\n" +
                            "event: " + event.eventType() + "\n" +
                            "data: " + event.data().replace("\n", "") + "\n\n";
                    output.write(frame.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    cursor = event.eventId();
                    if ("completed".equals(event.eventType()) || "failed".equals(event.eventType())) return;
                }
            }
        };
    }

    @PutMapping("/{taskId}/jd-draft")
    RecruitmentService.TaskDetail updateDraft(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                              @RequestBody RecruitmentService.UpdateDraftInput input,
                                              Authentication authentication) {
        return recruitment.updateDraft(CurrentUser.id(authentication), workspaceId, taskId, input);
    }

    @PutMapping("/{taskId}/resume-parse-draft")
    RecruitmentService.TaskDetail updateResumeParseDraft(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                                         @RequestBody RecruitmentService.UpdateResumeParseDraftInput input,
                                                         Authentication authentication) {
        return recruitment.updateResumeParseDraft(CurrentUser.id(authentication), workspaceId, taskId, input);
    }

    /**
     * 确认简历解析结果并发布到人才库（与 JD 草稿确认对称）。
     * 幂等：已发布或任务本就关联人才库候选人时，仅把草稿置为 CONFIRMED。
     */
    @PostMapping("/{taskId}/resume-parse-draft/confirm")
    RecruitmentService.TaskDetail confirmResumeParseDraft(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                                          Authentication authentication) {
        return recruitment.confirmResumeParse(CurrentUser.id(authentication), workspaceId, taskId);
    }

    /**
     * 触发一次 AI 简历解析 run。幂等键相同且 payload hash 一致时直接返回现有任务详情。
     * 解析完成后结果自动写入 resume_parse_drafts 新 revision。
     */
    @PostMapping("/{taskId}/resume-parse-runs")
    RecruitmentService.TaskDetail generateResumeParse(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestBody(required = false) RecruitmentService.GenerateResumeParseInput input,
                                                      Authentication authentication) {
        return recruitment.generateResumeParse(CurrentUser.id(authentication), workspaceId, taskId, idempotencyKey, input);
    }

    /**
     * 触发 AI 面试出题（同步）。读取任务的 linked_job_id + linked_candidate_id，生成面试题包后入库，
     * 并在右侧 AI 助手以消息形式返回题包摘要，与 JD 生成体验一致。
     */
    @PostMapping("/{taskId}/interview-kit-runs")
    RecruitmentService.TaskDetail generateInterviewKit(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                        @RequestBody(required = false) RecruitmentService.GenerateInterviewKitInput input,
                                                        Authentication authentication) {
        return recruitment.generateInterviewKit(CurrentUser.id(authentication), workspaceId, taskId, idempotencyKey, input);
    }

    /**
     * 获取单个简历源文件的预签名下载/预览 URL（10 分钟有效）。
     * 前端直接 window.open(url) 即可在新标签页预览 PDF/DOCX/TXT。
     */
    @GetMapping("/{taskId}/resume-source-files/{sourceFileId}/download")
    Map<String, Object> getResumeSourceFileDownload(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                                    @PathVariable UUID sourceFileId,
                                                    Authentication authentication) {
        UUID userId = CurrentUser.id(authentication);
        String url = recruitment.downloadResumeSourceFileUrl(userId, workspaceId, sourceFileId);
        long expiresMinutes = 10L;
        return Map.of(
                "url", url == null ? "" : url,
                "expiresAt", Instant.now().plusSeconds(expiresMinutes * 60L).toString(),
                "expiresMinutes", expiresMinutes
        );
    }

    @PostMapping("/{taskId}/jd-draft/confirm")
    JobService.JobView confirm(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                               @RequestParam UUID draftId,
                               Authentication authentication) {
        return recruitment.confirmDraft(CurrentUser.id(authentication), workspaceId, taskId, draftId);
    }

    private static long parseCursor(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Math.max(0, Long.parseLong(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static void sleep() {
        try { Thread.sleep(1_000); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
