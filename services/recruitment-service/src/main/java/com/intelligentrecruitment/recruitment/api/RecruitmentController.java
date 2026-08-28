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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

import java.util.List;
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

    @PostMapping("/{taskId}/jd-draft/confirm")
    JobService.JobView confirm(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                               Authentication authentication) {
        return recruitment.confirmDraft(CurrentUser.id(authentication), workspaceId, taskId);
    }

    /** 读取简历筛选六维评估配置 */
    @GetMapping("/{taskId}/screening-dims")
    RecruitmentService.ScreeningDimsView getScreeningDims(@PathVariable UUID workspaceId,
                                                          @PathVariable UUID taskId,
                                                          Authentication authentication) {
        String json = recruitment.getScreeningDims(CurrentUser.id(authentication), workspaceId, taskId);
        return new RecruitmentService.ScreeningDimsView(json);
    }

    /** 保存简历筛选六维评估配置（整体覆盖） */
    @PutMapping("/{taskId}/screening-dims")
    RecruitmentService.ScreeningDimsView updateScreeningDims(@PathVariable UUID workspaceId,
                                                            @PathVariable UUID taskId,
                                                            @RequestBody RecruitmentService.UpdateScreeningDimsInput input,
                                                            Authentication authentication) {
        String json = recruitment.updateScreeningDims(CurrentUser.id(authentication), workspaceId, taskId, input);
        return new RecruitmentService.ScreeningDimsView(json);
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
