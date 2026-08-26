package com.intelligentrecruitment.screening.api;

import com.intelligentrecruitment.screening.application.ScreeningService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class ScreeningController {

    private final ScreeningService screening;

    public ScreeningController(ScreeningService screening) {
        this.screening = screening;
    }

    @PostMapping("/screening-plans")
    ScreeningService.ScreeningPlanView createPlan(@PathVariable UUID workspaceId,
                                                   @RequestBody ScreeningService.PlanInput input,
                                                   Authentication authentication) {
        return screening.createPlan(CurrentUser.id(authentication), workspaceId, input);
    }

    @PutMapping("/screening-plans/{planId}")
    ScreeningService.ScreeningPlanView updatePlan(@PathVariable UUID workspaceId, @PathVariable UUID planId,
                                                   @RequestBody ScreeningService.PlanUpdateInput input,
                                                   Authentication authentication) {
        return screening.updatePlan(CurrentUser.id(authentication), workspaceId, planId, input);
    }

    @GetMapping("/screening-plans")
    List<ScreeningService.ScreeningPlanView> plans(@PathVariable UUID workspaceId, Authentication authentication) {
        return screening.listPlans(CurrentUser.id(authentication), workspaceId);
    }

    @PostMapping("/screening-quotes")
    ScreeningService.ScreeningQuoteView quote(@PathVariable UUID workspaceId,
                                               @RequestBody ScreeningService.QuoteInput input,
                                               Authentication authentication) {
        return screening.quote(CurrentUser.id(authentication), workspaceId, input);
    }

    @PostMapping("/screening-runs")
    ScreeningService.ScreeningRunDetail run(@PathVariable UUID workspaceId,
                                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @RequestBody ScreeningService.RunInput input,
                                             Authentication authentication) {
        return screening.run(CurrentUser.id(authentication), workspaceId, idempotencyKey, input);
    }

    @GetMapping("/screening-runs")
    List<ScreeningService.ScreeningRunSummary> runs(@PathVariable UUID workspaceId, Authentication authentication) {
        return screening.listRuns(CurrentUser.id(authentication), workspaceId);
    }

    @GetMapping("/screening-runs/{runId}")
    ScreeningService.ScreeningRunDetail get(@PathVariable UUID workspaceId, @PathVariable UUID runId,
                                             Authentication authentication) {
        return screening.getRun(CurrentUser.id(authentication), workspaceId, runId);
    }

    @PostMapping("/screening-runs/{runId}/retry-failed")
    ScreeningService.ScreeningRunDetail retry(@PathVariable UUID workspaceId, @PathVariable UUID runId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               Authentication authentication) {
        return screening.retryFailed(CurrentUser.id(authentication), workspaceId, runId, idempotencyKey);
    }

    @PostMapping("/screening-runs/{runId}/cancel")
    ScreeningService.ScreeningRunDetail cancel(@PathVariable UUID workspaceId, @PathVariable UUID runId,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                Authentication authentication) {
        return screening.cancel(CurrentUser.id(authentication), workspaceId, runId, idempotencyKey);
    }
}
