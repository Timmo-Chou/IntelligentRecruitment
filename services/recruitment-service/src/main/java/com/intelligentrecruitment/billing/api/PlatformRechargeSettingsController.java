package com.intelligentrecruitment.billing.api;

import com.intelligentrecruitment.billing.application.RechargeService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform/recharge-settings")
public class PlatformRechargeSettingsController {
    private final RechargeService recharge; private final PlatformAdminGuard guard;
    public PlatformRechargeSettingsController(RechargeService recharge, PlatformAdminGuard guard) { this.recharge = recharge; this.guard = guard; }
    @GetMapping
    RechargeService.ReceivingAccount get(@RequestHeader("X-Platform-Admin-Key") String key) { PlatformAdminInfo admin = guard.authenticate(key); guard.requirePermission(admin, "billing:read"); return recharge.activeReceivingAccount(); }
    @PutMapping
    RechargeService.ReceivingAccount save(@RequestHeader("X-Platform-Admin-Key") String key, @Valid @RequestBody ReceivingAccountRequest request) { PlatformAdminInfo admin = guard.authenticate(key); guard.requirePermission(admin, "billing:adjust"); return recharge.saveReceivingAccount(request.bankName(), request.beneficiaryName(), request.accountNumber(), request.contactPhone(), request.contactEmail()); }
    public record ReceivingAccountRequest(@NotBlank String bankName, @NotBlank String beneficiaryName, @NotBlank String accountNumber, String contactPhone, String contactEmail) {}
}
