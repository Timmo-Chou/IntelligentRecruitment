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
@RequestMapping("/api/v1/tenants/{tenantId}/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    // 统计
    @GetMapping("/stats")
    JobService.JobStats stats(@PathVariable UUID tenantId, Authentication authentication) {
        return jobService.stats(CurrentUser.id(authentication), tenantId);
    }

    // 列表（分页、搜索、筛选）
    @GetMapping
    JobService.JobListResult list(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            Authentication authentication) {
        return jobService.list(CurrentUser.id(authentication), tenantId, search, status, page, pageSize);
    }

    // 详情
    @GetMapping("/{jobId}")
    JobService.JobView get(@PathVariable UUID tenantId, @PathVariable UUID jobId, Authentication authentication) {
        return jobService.get(CurrentUser.id(authentication), tenantId, jobId);
    }

    // 创建
    @PostMapping
    JobService.JobView create(@PathVariable UUID tenantId, @Valid @RequestBody JobService.JobInput input,
                              Authentication authentication) {
        return jobService.create(CurrentUser.id(authentication), tenantId, input);
    }

    // 更新
    @PutMapping("/{jobId}")
    JobService.JobView update(@PathVariable UUID tenantId, @PathVariable UUID jobId,
                              @Valid @RequestBody JobService.JobInput input, Authentication authentication) {
        return jobService.update(CurrentUser.id(authentication), tenantId, jobId, input);
    }

    // 更新状态
    @PatchMapping("/{jobId}/status")
    JobService.JobView updateStatus(@PathVariable UUID tenantId, @PathVariable UUID jobId,
                                    @Valid @RequestBody StatusRequest request, Authentication authentication) {
        return jobService.updateStatus(CurrentUser.id(authentication), tenantId, jobId, request.status());
    }

    // 删除
    @DeleteMapping("/{jobId}")
    void delete(@PathVariable UUID tenantId, @PathVariable UUID jobId, Authentication authentication) {
        jobService.delete(CurrentUser.id(authentication), tenantId, jobId);
    }

    // 批量更新状态
    @PostMapping("/batch/status")
    void batchUpdateStatus(@PathVariable UUID tenantId,
                           @Valid @RequestBody JobService.BatchStatusRequest request,
                           Authentication authentication) {
        jobService.batchUpdateStatus(CurrentUser.id(authentication), tenantId, request.jobIds(), request.status());
    }

    // 批量删除
    @PostMapping("/batch/delete")
    void batchDelete(@PathVariable UUID tenantId,
                     @Valid @RequestBody JobService.BatchDeleteRequest request,
                     Authentication authentication) {
        jobService.batchDelete(CurrentUser.id(authentication), tenantId, request.jobIds());
    }

    // 版本历史
    @GetMapping("/{jobId}/versions")
    List<JobService.JobVersionView> versions(@PathVariable UUID tenantId, @PathVariable UUID jobId,
                                             Authentication authentication) {
        return jobService.versions(CurrentUser.id(authentication), tenantId, jobId);
    }

    public record StatusRequest(@NotBlank String status) {}
}
