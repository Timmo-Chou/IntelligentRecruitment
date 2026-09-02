package com.intelligentrecruitment.billing.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

/**
 * 定价配置服务。
 * 管理端可配置计费项（code/单价/状态），业务代码和用户端通过此服务查价。
 *
 * <p>code 取值由业务侧约定：
 * <ul>
 *   <li>JD_GENERATION —— JD 智能生成（按次）</li>
 *   <li>RESUME_PARSING —— 简历 AI 解析（按份）</li>
 *   <li>SCREENING —— AI 简历筛选（按候选人）</li>
 * </ul>
 */
@Service
public class PricingService {

    private final JdbcTemplate jdbc;

    public PricingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 列出所有计费项（管理端使用，包含 DISABLED）。
     */
    public List<PricingItemRow> listAll() {
        return jdbc.query("""
                SELECT id, code, name, description, billing_unit, unit_price_minor, currency, status, sort_order,
                       created_at, updated_at
                FROM pricing_items
                ORDER BY sort_order ASC, code ASC
                """, (rs, n) -> new PricingItemRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("billing_unit"),
                rs.getLong("unit_price_minor"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ));
    }

    /**
     * 列出启用中的计费项（用户端展示 + 业务代码查价）。
     */
    public List<PricingItemRow> listActive() {
        return jdbc.query("""
                SELECT id, code, name, description, billing_unit, unit_price_minor, currency, status, sort_order,
                       created_at, updated_at
                FROM pricing_items
                WHERE status = 'ACTIVE'
                ORDER BY sort_order ASC, code ASC
                """, (rs, n) -> new PricingItemRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("billing_unit"),
                rs.getLong("unit_price_minor"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ));
    }

    /**
     * 按业务 code 查单价（分），业务代码使用。
     * 找不到或已禁用时返回 null——调用方自行决定是默认值还是拒绝执行。
     */
    public Long findUnitPriceMinor(String code) {
        List<Long> rows = jdbc.query(
                "SELECT unit_price_minor FROM pricing_items WHERE code = ? AND status = 'ACTIVE'",
                (rs, n) -> rs.getLong("unit_price_minor"), code);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 按 code 获取完整计费项详情。
     */
    public PricingItemRow findByCode(String code) {
        List<PricingItemRow> rows = jdbc.query("""
                SELECT id, code, name, description, billing_unit, unit_price_minor, currency, status, sort_order,
                       created_at, updated_at
                FROM pricing_items WHERE code = ?
                """, (rs, n) -> new PricingItemRow(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("billing_unit"),
                rs.getLong("unit_price_minor"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getInt("sort_order"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ), code);
        if (rows.isEmpty()) {
            throw new ApiException("PRICING_ITEM_NOT_FOUND", "计费项不存在：" + code, HttpStatus.NOT_FOUND);
        }
        return rows.getFirst();
    }

    /**
     * 管理端：新建计费项。
     */
    @Transactional
    public PricingItemRow create(String code, String name, String description, String billingUnit,
                                  long unitPriceMinor, String currency, int sortOrder) {
        if (!List.of("PER_USE", "PER_ITEM", "PER_CANDIDATE").contains(billingUnit)) {
            throw new ApiException("INVALID_BILLING_UNIT", "计费方式不合法", HttpStatus.BAD_REQUEST);
        }
        if (unitPriceMinor < 0) {
            throw new ApiException("INVALID_PRICE", "单价不能为负", HttpStatus.BAD_REQUEST);
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO pricing_items (id, code, name, description, billing_unit, unit_price_minor, currency, status, sort_order, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, id, code, name, description, billingUnit, unitPriceMinor, currency, sortOrder,
                timestamp(now), timestamp(now));
        return findByCode(code);
    }

    /**
     * 管理端：更新计费项（名称/描述/单价/排序/状态）。
     * 只传需要修改的字段，null 表示保持不变。
     * code 不允许修改——它是业务侧依赖的常量。
     */
    @Transactional
    public PricingItemRow update(String code, String name, String description,
                                  Long unitPriceMinor, Integer sortOrder, String status) {
        // 存在性校验
        PricingItemRow existing = findByCode(code);

        String newName = name != null ? name.trim() : existing.name();
        String newDesc = description != null ? description.trim() : existing.description();
        long newPrice = unitPriceMinor != null ? unitPriceMinor : existing.unitPriceMinor();
        int newSort = sortOrder != null ? sortOrder : existing.sortOrder();
        String newStatus = status != null ? status : existing.status();

        if (newPrice < 0) {
            throw new ApiException("INVALID_PRICE", "单价不能为负", HttpStatus.BAD_REQUEST);
        }
        if (!List.of("ACTIVE", "DISABLED").contains(newStatus)) {
            throw new ApiException("INVALID_STATUS", "状态只能是 ACTIVE 或 DISABLED", HttpStatus.BAD_REQUEST);
        }

        jdbc.update("""
                UPDATE pricing_items SET name=?, description=?, unit_price_minor=?, sort_order=?, status=?, updated_at=?
                WHERE code=?
                """, newName, newDesc, newPrice, newSort, newStatus,
                timestamp(Instant.now()), code);

        return findByCode(code);
    }

    /** 保留兼容：不带 status 的更新 */
    public PricingItemRow update(String code, String name, String description,
                                  Long unitPriceMinor, Integer sortOrder) {
        return update(code, name, description, unitPriceMinor, sortOrder, null);
    }

    /**
     * 管理端：启用 / 停用。
     */
    @Transactional
    public PricingItemRow setStatus(String code, String status) {
        if (!List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new ApiException("INVALID_STATUS", "状态只能是 ACTIVE 或 DISABLED", HttpStatus.BAD_REQUEST);
        }
        findByCode(code); // 校验存在
        jdbc.update("UPDATE pricing_items SET status=?, updated_at=? WHERE code=?",
                status, timestamp(Instant.now()), code);
        return findByCode(code);
    }

    // ---- 数据记录 ----

    public record PricingItemRow(
            UUID id,
            String code,
            String name,
            String description,
            String billingUnit,          // PER_USE / PER_ITEM / PER_CANDIDATE
            long unitPriceMinor,         // 单价（分）
            String currency,
            String status,                // ACTIVE / DISABLED
            int sortOrder,
            Instant createdAt,
            Instant updatedAt
    ) {}
}
