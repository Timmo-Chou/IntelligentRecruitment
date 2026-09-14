package com.intelligentrecruitment.platform.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理端账本与余额控制器。
 * 数据来源：钱包和流水从 BOSS 读取；余额调整暂由 BOSS 内部接口负责（当前返回 501）。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformBillingController {

    private final BossControlPlaneClient boss;
    private final PlatformAdminGuard guard;

    public PlatformBillingController(BossControlPlaneClient boss, PlatformAdminGuard guard) {
        this.boss = boss;
        this.guard = guard;
    }

    /**
     * 平台管理端查看指定企业的余额视图。
     * 统一走 BOSS companyWallet（已无 workspace 概念，传入的 companyId 即 companyId）。
     */
    @GetMapping("/companies/{companyId}/billing")
    public AdminBillingView workspaceBilling(@PathVariable UUID companyId,
                                             @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "billing:read");

        JsonNode wallet = boss.internalCompanyWallet(companyId);

        long availableAmountMinor = wallet.path("total_micro").asLong(0) / 10000;
        long reservedAmountMinor = (wallet.path("reserved_gift_micro").asLong(0)
                + wallet.path("reserved_recharge_micro").asLong(0)) / 10000;
        String currency = wallet.path("currency").asText("CNY");

        return new AdminBillingView(currency, availableAmountMinor, reservedAmountMinor, List.of());
    }

    /**
     * 平台管理端查看账本流水。
     * 调用 BOSS companyStatements（传入的 companyId 即 companyId）。
     */
    @GetMapping("/billing")
    public LedgerPage billingLedger(@RequestParam UUID companyId,
                                    @RequestParam(defaultValue = "100") int pageSize,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "billing:read");

        List<LedgerItem> items = new ArrayList<>();
        long total = 0;

        JsonNode statements = boss.internalCompanyStatements(companyId);
        // BOSS statements 可能是数组或带 items 的分页对象
        JsonNode array = statements.isArray() ? statements : statements.path("items");
        if (array.isArray()) {
            for (JsonNode item : array) {
                items.add(mapLedgerItem(item));
            }
            total = statements.path("total").asLong(items.size());
        }

        return new LedgerPage(items, total, page, pageSize);
    }

    /**
     * 平台管理端余额调整。
     * 当前 BOSS 未提供内部调整接口，返回 501 提示运营人员通过 BOSS 后台操作。
     */
    @PostMapping("/companies/{companyId}/billing/adjustments")
    public AdjustmentResult adjustBalance(@PathVariable UUID companyId,
                                          @Valid @RequestBody AdjustmentRequest request,
                                          @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "billing:adjust");

        // BOSS 当前未开放内部余额调整接口，返回 501 提示
        throw new ApiException("NOT_IMPLEMENTED",
                "余额调整功能暂未开放，请通过 BOSS 管理后台操作", HttpStatus.NOT_IMPLEMENTED);
    }

    /**
     * 将 BOSS statement 映射为前端账本条目。
     */
    private LedgerItem mapLedgerItem(JsonNode item) {
        String rawType = item.path("type").asText(item.path("entry_type").asText(""));
        String entryType = mapEntryType(rawType);
        long amountMinor = item.path("amount_minor").asLong(item.path("amount").asLong(0));
        String reference = item.path("business_reference").asText(item.path("reference").asText(""));
        String reason = item.path("reason").asText("");
        String operator = item.path("operator_name").asText(item.path("operator").asText(""));
        String createdAt = item.path("created_at").asText(item.path("occurred_at").asText(""));
        String id = item.path("id").asText(item.path("statement_id").asText(""));
        return new LedgerItem(id, entryType, amountMinor, reference, reason, operator, createdAt);
    }

    /**
     * 将 BOSS 的流水类型映射为前端统一的 entryType。
     */
    private String mapEntryType(String rawType) {
        if (rawType == null || rawType.isBlank()) return "UNKNOWN";
        String upper = rawType.toUpperCase();
        return switch (upper) {
            case "RECHARGE", "TOP_UP", "DEPOSIT", "INCOME" -> "INCOME";
            case "CONSUME", "SPEND", "DEDUCT", "EXPENSE", "SETTLEMENT" -> "EXPENSE";
            case "ADJUST", "ADJUSTMENT" -> "ADJUSTMENT";
            case "RESERVE", "FREEZE" -> "RESERVE";
            case "RELEASE", "UNFREEZE" -> "RELEASE";
            case "EXPIRE" -> "EXPIRE";
            case "GRANT" -> "GRANT";
            default -> upper;
        };
    }

    // ==================== 响应 DTO ====================

    public record AdminBillingView(
            String currency,
            long availableAmountMinor,
            long reservedAmountMinor,
            List<AdminCreditLot> creditLots
    ) {}

    public record AdminCreditLot(
            String id,
            String sourceType,
            long originalAmountMinor,
            long availableAmountMinor,
            String expiresAt,
            String status
    ) {}

    public record LedgerItem(
            String id,
            String entryType,
            long amountMinor,
            String businessReference,
            String reason,
            String operatorName,
            String createdAt
    ) {}

    public record LedgerPage(
            List<LedgerItem> items,
            long total,
            int page,
            int pageSize
    ) {}

    public record AdjustmentRequest(
            long amountMinor,
            @NotBlank String reference,
            @NotBlank String reason
    ) {}

    public record AdjustmentResult(
            String id,
            String status
    ) {}
}
