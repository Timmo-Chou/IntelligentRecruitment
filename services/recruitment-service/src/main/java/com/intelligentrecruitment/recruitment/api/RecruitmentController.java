package com.intelligentrecruitment.recruitment.api;

import com.intelligentrecruitment.jobs.application.JobService;
import com.intelligentrecruitment.recruitment.application.RecruitmentService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/{taskId}/messages")
    RecruitmentService.TaskDetail message(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                          @RequestBody RecruitmentService.MessageInput input,
                                          Authentication authentication) {
        return recruitment.addMessage(CurrentUser.id(authentication), workspaceId, taskId, input);
    }

    @PostMapping("/{taskId}/jd-runs")
    RecruitmentService.TaskDetail generate(@PathVariable UUID workspaceId, @PathVariable UUID taskId,
                                           @RequestHeader("Idempotency-Key") String idempotencyKey,
                                           @RequestBody RecruitmentService.GenerateJdInput input,
                                           Authentication authentication) {
        return recruitment.generateJd(CurrentUser.id(authentication), workspaceId, taskId, idempotencyKey, input);
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
}
