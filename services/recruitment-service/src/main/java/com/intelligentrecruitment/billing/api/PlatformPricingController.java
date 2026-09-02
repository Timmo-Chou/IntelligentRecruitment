package com.intelligentrecruitment.billing.api;

import com.intelligentrecruitment.billing.application.PricingService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理端定价配置 API。
 * 路径：/api/v1/platform/pricing
 */
@RestController
@RequestMapping("/api/v1/platform/pricing")
public class PlatformPricingController {

    private final PricingService pricingService;
    private final PlatformAdminGuard guard;

    public PlatformPricingController(PricingService pricingService, PlatformAdminGuard guard) {
        this.pricingService = pricingService;
        this.guard = guard;
    }

    /**
     * 列出所有计费项（含 DISABLED）。
     */
    @GetMapping
    List<PricingService.PricingItemRow> listAll(@RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "pricing:read");
        return pricingService.listAll();
    }

    /**
     * 按 code 查询单个计费项。
     */
    @GetMapping("/{code}")
    PricingService.PricingItemRow findByCode(@PathVariable String code,
                                              @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "pricing:read");
        return pricingService.findByCode(code);
    }

    /**
     * 新建计费项。
     */
    @PostMapping
    PricingService.PricingItemRow create(@RequestHeader("X-Platform-Admin-Key") String key,
                                          @Valid @RequestBody CreateRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "pricing:write");
        return pricingService.create(
                request.code(), request.name(), request.description(),
                request.billingUnit(), request.unitPriceMinor(),
                request.currency() != null ? request.currency() : "CNY",
                request.sortOrder() != null ? request.sortOrder() : 0);
    }

    /**
     * 更新计费项（名称/描述/单价/排序/状态）。code 不允许改。
     * 前端切换启用/停用也通过这个接口（传 status 字段）。
     */
    @PutMapping("/{code}")
    PricingService.PricingItemRow update(@PathVariable String code,
                                          @RequestHeader("X-Platform-Admin-Key") String key,
                                          @Valid @RequestBody UpdateRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "pricing:write");
        return pricingService.update(code, request.name(), request.description(),
                request.unitPriceMinor(), request.sortOrder(), request.status());
    }

    // ---- 请求体 ----

    public record CreateRequest(
            @NotBlank String code,
            @NotBlank String name,
            String description,
            @NotBlank String billingUnit,   // PER_USE / PER_ITEM / PER_CANDIDATE
            @PositiveOrZero long unitPriceMinor,
            String currency,                // 默认 CNY
            Integer sortOrder               // 默认 0
    ) {}

    public record UpdateRequest(
            String name,
            String description,
            @PositiveOrZero Long unitPriceMinor,
            Integer sortOrder,
            /** ACTIVE / DISABLED —— 传 null 则保持不变 */
            String status
    ) {}
}
