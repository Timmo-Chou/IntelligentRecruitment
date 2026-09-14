package com.intelligentrecruitment.screening.api;

import com.intelligentrecruitment.screening.application.ScreeningService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class ScreeningController {

    private final ScreeningService screening;

    public ScreeningController(ScreeningService screening) {
        this.screening = screening;
    }

    @PostMapping("/screening-plans")
    ScreeningService.ScreeningPlanView createPlan(@PathVariable UUID companyId,
                                                   @RequestBody ScreeningService.PlanInput input,
                                                   Authentication authentication) {
        return screening.createPlan(CurrentUser.id(authentication), companyId, input);
    }

    @PutMapping("/screening-plans/{planId}")
    ScreeningService.ScreeningPlanView updatePlan(@PathVariable UUID companyId, @PathVariable UUID planId,
                                                   @RequestBody ScreeningService.PlanUpdateInput input,
                                                   Authentication authentication) {
        return screening.updatePlan(CurrentUser.id(authentication), companyId, planId, input);
    }

    @GetMapping("/screening-plans")
    List<ScreeningService.ScreeningPlanView> plans(@PathVariable UUID companyId,
                                                    @RequestParam(required = false) UUID recruitmentTaskId,
                                                    Authentication authentication) {
        return screening.listPlans(CurrentUser.id(authentication), companyId, recruitmentTaskId);
    }

    @PostMapping("/screening-quotes")
    ScreeningService.ScreeningQuoteView quote(@PathVariable UUID companyId,
                                               @RequestBody ScreeningService.QuoteInput input,
                                               Authentication authentication) {
        return screening.quote(CurrentUser.id(authentication), companyId, input);
    }

    @GetMapping("/screening-pricing")
    ScreeningService.ScreeningPricingView pricing(@PathVariable UUID companyId, Authentication authentication) {
        return screening.pricing(CurrentUser.id(authentication), companyId);
    }

    @PostMapping("/screening-runs")
    ScreeningService.ScreeningRunDetail run(@PathVariable UUID companyId,
                                             @RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @RequestBody ScreeningService.RunInput input,
                                             Authentication authentication) {
        return screening.run(CurrentUser.id(authentication), companyId, idempotencyKey, input);
    }

    @GetMapping("/screening-runs")
    List<ScreeningService.ScreeningRunSummary> runs(@PathVariable UUID companyId,
                                                     @RequestParam(required = false) UUID recruitmentTaskId,
                                                     Authentication authentication) {
        return screening.listRuns(CurrentUser.id(authentication), companyId, recruitmentTaskId);
    }

    @GetMapping("/screening-runs/{runId}")
    ScreeningService.ScreeningRunDetail get(@PathVariable UUID companyId, @PathVariable UUID runId,
                                             Authentication authentication) {
        return screening.getRun(CurrentUser.id(authentication), companyId, runId);
    }

    @PostMapping("/screening-runs/{runId}/retry-quote")
    ScreeningService.ScreeningQuoteView retryQuote(@PathVariable UUID companyId, @PathVariable UUID runId,
                                                    Authentication authentication) {
        return screening.retryQuote(CurrentUser.id(authentication), companyId, runId);
    }

    @PostMapping("/screening-runs/{runId}/retry-failed")
    ScreeningService.ScreeningRunDetail retry(@PathVariable UUID companyId, @PathVariable UUID runId,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @RequestBody ScreeningService.RetryInput input,
                                               Authentication authentication) {
        return screening.retryFailed(CurrentUser.id(authentication), companyId, runId, idempotencyKey, input);
    }

    @PostMapping("/screening-runs/{runId}/cancel")
    ScreeningService.ScreeningRunDetail cancel(@PathVariable UUID companyId, @PathVariable UUID runId,
                                                @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                Authentication authentication) {
        return screening.cancel(CurrentUser.id(authentication), companyId, runId, idempotencyKey);
    }
}
