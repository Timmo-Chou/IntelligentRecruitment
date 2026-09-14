package com.intelligentrecruitment.platform.pricing.api;

import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理端定价配置控制器。
 * 定价数据存储在本地 pricing_items 表（招聘系统特有配置，不从 BOSS 读取）。
 */
@RestController
@RequestMapping("/api/v1/platform/pricing")
public class PlatformPricingController {

    private final PlatformAdminGuard guard;
    private final JdbcTemplate jdbc;

    public PlatformPricingController(PlatformAdminGuard guard, JdbcTemplate jdbc) {
        this.guard = guard;
        this.jdbc = jdbc;
    }

    /**
     * 获取所有定价项列表，按 sort_order 排序。
     */
    @GetMapping
    public List<PricingItem> listPricing(@RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "pricing:read");

        return jdbc.query("""
                        SELECT id, code, name, description, billing_unit, unit_price_minor,
                               currency, status, sort_order, created_at, updated_at
                        FROM pricing_items
                        ORDER BY sort_order ASC, created_at ASC
                        """,
                (rs, n) -> new PricingItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("billing_unit"),
                        rs.getLong("unit_price_minor"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getInt("sort_order"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        rs.getTimestamp("updated_at").toInstant().toString()
                ));
    }

    /**
     * 更新指定定价项。
     * 支持部分更新：仅更新请求中提供的字段。
     */
    @PutMapping("/{code}")
    public PricingItem updatePricing(@PathVariable String code,
                                     @RequestBody UpdatePricingRequest request,
                                     @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "pricing:write");

        // 先查询当前记录
        List<PricingItem> existing = jdbc.query("""
                        SELECT id, code, name, description, billing_unit, unit_price_minor,
                               currency, status, sort_order, created_at, updated_at
                        FROM pricing_items WHERE code = ?
                        """,
                (rs, n) -> new PricingItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("billing_unit"),
                        rs.getLong("unit_price_minor"),
                        rs.getString("currency"),
                        rs.getString("status"),
                        rs.getInt("sort_order"),
                        rs.getTimestamp("created_at").toInstant().toString(),
                        rs.getTimestamp("updated_at").toInstant().toString()
                ), code);

        if (existing.isEmpty()) {
            throw new ApiException("PRICING_NOT_FOUND", "定价项不存在: " + code, HttpStatus.NOT_FOUND);
        }

        PricingItem current = existing.getFirst();

        // 合并更新字段（请求中为 null 的字段保持原值）
        String name = request.name() != null ? request.name() : current.name();
        String description = request.description() != null ? request.description() : current.description();
        Long unitPriceMinor = request.unitPriceMinor() != null ? request.unitPriceMinor() : current.unitPriceMinor();
        Integer sortOrder = request.sortOrder() != null ? request.sortOrder() : current.sortOrder();
        String status = request.status() != null ? request.status() : current.status();

        jdbc.update("""
                        UPDATE pricing_items
                        SET name = ?, description = ?, unit_price_minor = ?, sort_order = ?,
                            status = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE code = ?
                        """,
                name, description, unitPriceMinor, sortOrder, status, code);

        // 返回更新后的记录
        return listPricing(key).stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElse(current);
    }

    // ==================== DTO ====================

    public record PricingItem(
            UUID id,
            String code,
            String name,
            String description,
            String billingUnit,
            long unitPriceMinor,
            String currency,
            String status,
            int sortOrder,
            String createdAt,
            String updatedAt
    ) {}

    /**
     * 更新定价请求。所有字段均为可选（部分更新）。
     */
    public record UpdatePricingRequest(
            String name,
            String description,
            Long unitPriceMinor,
            Integer sortOrder,
            String status
    ) {}
}
