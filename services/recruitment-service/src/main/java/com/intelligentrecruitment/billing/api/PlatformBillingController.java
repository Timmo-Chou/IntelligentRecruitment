package com.intelligentrecruitment.billing.api;

import com.intelligentrecruitment.billing.application.BillingService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/platform/workspaces/{workspaceId}/billing")
public class PlatformBillingController {
    private final BillingService billing;
    private final PlatformAdminGuard guard;

    public PlatformBillingController(BillingService billing, PlatformAdminGuard guard) {
        this.billing = billing;
        this.guard = guard;
    }

    @PostMapping("/adjustments")
    void adjust(@PathVariable UUID workspaceId, @RequestHeader("X-Platform-Admin-Key") String key,
                @Valid @RequestBody AdjustmentRequest request) {
        guard.require(key);
        billing.adjust(workspaceId, request.amountMinor(), request.reference(), request.reason());
    }

    @PostMapping("/settlements")
    BillingService.ReservationView settle(@PathVariable UUID workspaceId,
                                          @RequestHeader("X-Platform-Admin-Key") String key,
                                          @Valid @RequestBody SettlementRequest request) {
        guard.require(key);
        return billing.settleSystem(workspaceId, request.businessReference(), request.actualAmountMinor());
    }

    public record AdjustmentRequest(@NotNull Long amountMinor, @NotBlank String reference, @NotBlank String reason) {}
    public record SettlementRequest(@NotBlank String businessReference, @PositiveOrZero long actualAmountMinor) {}
}
