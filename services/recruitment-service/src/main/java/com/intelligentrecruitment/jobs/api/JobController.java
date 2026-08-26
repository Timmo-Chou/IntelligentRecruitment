package com.intelligentrecruitment.jobs.api;

import com.intelligentrecruitment.jobs.application.JobService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    // 统计
    @GetMapping("/stats")
    JobService.JobStats stats(@PathVariable UUID workspaceId, Authentication authentication) {
        return jobService.stats(CurrentUser.id(authentication), workspaceId);
    }

    // 列表（分页、搜索、筛选）
    @GetMapping
    JobService.JobListResult list(
            @PathVariable UUID workspaceId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication authentication) {
        return jobService.list(CurrentUser.id(authentication), workspaceId, search, status, page, pageSize);
    }

    // 详情
    @GetMapping("/{jobId}")
    JobService.JobView get(@PathVariable UUID workspaceId, @PathVariable UUID jobId, Authentication authentication) {
        return jobService.get(CurrentUser.id(authentication), workspaceId, jobId);
    }

    // 创建
    @PostMapping
    JobService.JobView create(@PathVariable UUID workspaceId, @Valid @RequestBody JobService.JobInput input,
                              Authentication authentication) {
        return jobService.create(CurrentUser.id(authentication), workspaceId, input);
    }

    // 更新
    @PutMapping("/{jobId}")
    JobService.JobView update(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
                              @Valid @RequestBody JobService.JobInput input, Authentication authentication) {
        return jobService.update(CurrentUser.id(authentication), workspaceId, jobId, input);
    }

    // 更新状态
    @PatchMapping("/{jobId}/status")
    JobService.JobView updateStatus(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
                                    @Valid @RequestBody StatusRequest request, Authentication authentication) {
        return jobService.updateStatus(CurrentUser.id(authentication), workspaceId, jobId, request.status());
    }

    // 删除
    @DeleteMapping("/{jobId}")
    void delete(@PathVariable UUID workspaceId, @PathVariable UUID jobId, Authentication authentication) {
        jobService.delete(CurrentUser.id(authentication), workspaceId, jobId);
    }

    // 批量更新状态
    @PostMapping("/batch/status")
    void batchUpdateStatus(@PathVariable UUID workspaceId,
                           @Valid @RequestBody JobService.BatchStatusRequest request,
                           Authentication authentication) {
        jobService.batchUpdateStatus(CurrentUser.id(authentication), workspaceId, request.jobIds(), request.status());
    }

    // 批量删除
    @PostMapping("/batch/delete")
    void batchDelete(@PathVariable UUID workspaceId,
                     @Valid @RequestBody JobService.BatchDeleteRequest request,
                     Authentication authentication) {
        jobService.batchDelete(CurrentUser.id(authentication), workspaceId, request.jobIds());
    }

    // 版本历史
    @GetMapping("/{jobId}/versions")
    List<JobService.JobVersionView> versions(@PathVariable UUID workspaceId, @PathVariable UUID jobId,
                                             Authentication authentication) {
        return jobService.versions(CurrentUser.id(authentication), workspaceId, jobId);
    }

    public record StatusRequest(@NotBlank String status) {}
}