package com.intelligentrecruitment.billing.api;

import com.intelligentrecruitment.billing.application.BillingService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}")
public class BillingController {
    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/billing")
    BillingService.BillingView billing(@PathVariable UUID workspaceId, Authentication authentication) {
        return billingService.view(CurrentUser.id(authentication), workspaceId);
    }

    @PostMapping("/billing/reservations")
    BillingService.ReservationView reserve(@PathVariable UUID workspaceId, @Valid @RequestBody ReserveRequest request,
                                           Authentication authentication) {
        return billingService.reserve(CurrentUser.id(authentication), workspaceId,
                request.businessReference(), request.amountMinor());
    }

    public record ReserveRequest(@NotBlank String businessReference, @Positive long amountMinor) {}
}
