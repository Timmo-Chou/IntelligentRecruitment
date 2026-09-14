package com.intelligentrecruitment.screening.api;

import com.intelligentrecruitment.screening.application.ScreeningService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class ScreeningController {

    private final ScreeningService screening;

    public ScreeningController(ScreeningService screening) {
        this.screening = screening;
    }

    @PostMapping("/screening-plans")
    ScreeningService.ScreeningPlanView createPlan(@PathVariable UUID tenantId,
                                                   @RequestBody ScreeningService.PlanInput input,
                                                   Authentication authentication) {
        return screening.createPlan(CurrentUser.id(authentication), tenantId, input);
    }

    @PutMapping("/screening-plans/{planId}")
    ScreeningService.ScreeningPlanView updatePlan(@PathVariable UUID tenantId, @PathVariable UUID planId,
                                                   @RequestBody ScreeningService.PlanUpdateInput input,
                                                   Authentication authentication) {
        return screening.updatePlan(CurrentUser.id(authentication), tenantId, planId, input);
    }

    @GetMapping("/screening-plans")
    List<ScreeningService.ScreeningPlanView> plans(@PathVariable UUID tenantId,
                                                    @RequestParam(required = false) UUID recruitmentTaskId,
                                                    Authentication authentication) {
        return screening.listPlans(CurrentUser.id(authentication), tenantId, recruitmentTaskId);
    }

    @PostMapping("/screening-quotes")
    ScreeningService.ScreeningQuoteView quote(@PathVariable UUID tenantId,
                                               @RequestBody ScreeningService.QuoteInput input,
                                               Authentication authentication) {
        return screening.quote(CurrentUser.id(authentication), tenantId, input);
    }

    @GetMapping("/screening-pricing")
    ScreeningService.ScreeningPricingView pricing(@PathVariable UUID tenantId, Authentication authentication) {
        return screening.pricing(CurrentUser.id(authentication), tenantId);
    }

    @PostMapping("/screening-runs")
    ScreeningService.ScreeningRunDetail run(@PathVariable UUID tenantId,
                                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @RequestBody ScreeningService.RunInput input,
                                             Authentication authentication) {
        return screening.run(CurrentUser.id(authentication), tenantId, idempotencyKey, input);
    }

    @GetMapping("/screening-runs")
    List<ScreeningService.ScreeningRunSummary> runs(@PathVariable UUID tenantId,
                                                     @RequestParam(required = false) UUID recruitmentTaskId,
                                                     Authentication authentication) {
        return screening.listRuns(CurrentUser.id(authentication), tenantId, recruitmentTaskId);
    }

    @GetMapping("/screening-runs/{runId}")
    ScreeningService.ScreeningRunDetail get(@PathVariable UUID tenantId, @PathVariable UUID runId,
                                             Authentication authentication) {
        return screening.getRun(CurrentUser.id(authentication), tenantId, runId);
    }

    @PostMapping("/screening-runs/{runId}/retry-quote")
    ScreeningService.ScreeningQuoteView retryQuote(@PathVariable UUID tenantId, @PathVariable UUID runId,
                                                    Authentication authentication) {
        return screening.retryQuote(CurrentUser.id(authentication), tenantId, runId);
    }

    @PostMapping("/screening-runs/{runId}/retry-failed")
    ScreeningService.ScreeningRunDetail retry(@PathVariable UUID tenantId, @PathVariable UUID runId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @RequestBody ScreeningService.RetryInput input,
                                               Authentication authentication) {
        return screening.retryFailed(CurrentUser.id(authentication), tenantId, runId, idempotencyKey, input);
    }

    @PostMapping("/screening-runs/{runId}/cancel")
    ScreeningService.ScreeningRunDetail cancel(@PathVariable UUID tenantId, @PathVariable UUID runId,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                Authentication authentication) {
        return screening.cancel(CurrentUser.id(authentication), tenantId, runId, idempotencyKey);
    }
}
