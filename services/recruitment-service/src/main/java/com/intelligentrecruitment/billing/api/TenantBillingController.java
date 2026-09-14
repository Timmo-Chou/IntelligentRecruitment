package com.intelligentrecruitment.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.security.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only Tenant wallet and package view; BOSS remains the authority. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/billing")
public class TenantBillingController {
    private final BossControlPlaneClient boss;
    private final ObjectMapper json;
    public TenantBillingController(BossControlPlaneClient boss, ObjectMapper json) { this.boss = boss; this.json = json; }

    @GetMapping
    Map<String,Object> billing(@PathVariable UUID tenantId, Authentication authentication) {
        String token = CurrentUser.bossAccessToken(authentication);
        JsonNode wallet = boss.tenantWallet(token, tenantId);
        Map<String,Object> result = json.convertValue(wallet, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        result.put("packages", json.convertValue(boss.tenantPackages(token, tenantId), new com.fasterxml.jackson.core.type.TypeReference<Object>(){}));
        result.put("availableAmountMicro", wallet.path("total_micro").asLong(0));
        result.put("reservedAmountMicro", wallet.path("reserved_gift_micro").asLong(0) + wallet.path("reserved_recharge_micro").asLong(0));
        return result;
    }
}
