package com.intelligentrecruitment.billing.api;

import com.intelligentrecruitment.billing.application.BillingService;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 平台账本管理 Controller。
 * - /platform/billing?workspaceId=... 管理员视角查询账本流水
 * - /platform/workspaces/{workspaceId}/billing/** 管理员调整 / 结算
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformBillingController {
    private final BillingService billing;
    private final PlatformAdminGuard guard;

    public PlatformBillingController(BillingService billing, PlatformAdminGuard guard) {
        this.billing = billing;
        this.guard = guard;
    }

    /**
     * 按工作空间查询账本流水（分页）。
     */
    @GetMapping("/billing")
    BillingService.PagedLedgerEntries list(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @RequestParam UUID workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        guard.require(key);
        if (pageSize <= 0 || pageSize > 500) {
            throw new ApiException("INVALID_PAGE_SIZE", "pageSize 必须在 1-500 之间", HttpStatus.BAD_REQUEST);
        }
        return billing.listLedgerEntries(workspaceId, page, pageSize);
    }

    /**
     * 查询账户余额和额度批次（管理员视角）。
     */
    @GetMapping("/workspaces/{workspaceId}/billing")
    BillingService.AdminBillingView view(
            @PathVariable UUID workspaceId,
            @RequestHeader("X-Platform-Admin-Key") String key) {
        guard.require(key);
        return billing.viewForAdmin(workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/billing/adjustments")
    void adjust(@PathVariable UUID workspaceId, @RequestHeader("X-Platform-Admin-Key") String key,
                @Valid @RequestBody AdjustmentRequest request) {
        guard.require(key);
        billing.adjust(workspaceId, request.amountMinor(), request.reference(), request.reason());
    }

    @PostMapping("/workspaces/{workspaceId}/billing/settlements")
    BillingService.ReservationView settle(@PathVariable UUID workspaceId,
                                          @RequestHeader("X-Platform-Admin-Key") String key,
                                          @Valid @RequestBody SettlementRequest request) {
        guard.require(key);
        return billing.settleSystem(workspaceId, request.businessReference(), request.actualAmountMinor());
    }

    public record AdjustmentRequest(@NotNull Long amountMinor, @NotBlank String reference, @NotBlank String reason) {}
    public record SettlementRequest(@NotBlank String businessReference, @PositiveOrZero long actualAmountMinor) {}
}
