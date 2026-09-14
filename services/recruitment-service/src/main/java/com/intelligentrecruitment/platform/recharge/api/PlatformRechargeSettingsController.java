package com.intelligentrecruitment.platform.recharge.api;

import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理端收款账户配置控制器。
 * 数据存储在本地 recharge_receiving_accounts 表（招聘系统特有配置）。
 * 表中仅保留一条 ACTIVE 记录作为当前生效的收款账户。
 */
@RestController
@RequestMapping("/api/v1/platform/recharge-settings")
public class PlatformRechargeSettingsController {

    private final PlatformAdminGuard guard;
    private final JdbcTemplate jdbc;

    public PlatformRechargeSettingsController(PlatformAdminGuard guard, JdbcTemplate jdbc) {
        this.guard = guard;
        this.jdbc = jdbc;
    }

    /**
     * 获取当前生效的收款账户。
     * 若未配置则返回 null。
     */
    @GetMapping
    public ReceivingAccount getSettings(@RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "billing:read");

        List<ReceivingAccount> accounts = jdbc.query("""
                        SELECT id, bank_name, beneficiary_name, account_number, contact_phone, contact_email
                        FROM recharge_receiving_accounts
                        WHERE status = 'ACTIVE'
                        ORDER BY created_at DESC
                        LIMIT 1
                        """,
                (rs, n) -> new ReceivingAccount(
                        rs.getObject("id", UUID.class),
                        rs.getString("bank_name"),
                        rs.getString("beneficiary_name"),
                        rs.getString("account_number"),
                        rs.getString("contact_phone"),
                        rs.getString("contact_email")
                ));

        return accounts.isEmpty() ? null : accounts.getFirst();
    }

    /**
     * 保存收款账户。
     * 采用 upsert 逻辑：将已有 ACTIVE 记录置为 DISABLED，再插入新记录。
     */
    @PutMapping
    public ReceivingAccount saveSettings(@Valid @RequestBody SaveReceivingAccountRequest request,
                                         @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "billing:adjust");

        UUID id = UUID.randomUUID();

        // 将旧记录置为非活跃
        jdbc.update("UPDATE recharge_receiving_accounts SET status = 'DISABLED', updated_at = CURRENT_TIMESTAMP WHERE status = 'ACTIVE'");

        // 插入新记录
        jdbc.update("""
                        INSERT INTO recharge_receiving_accounts
                            (id, bank_name, beneficiary_name, account_number, contact_phone, contact_email, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                id, request.bankName(), request.beneficiaryName(), request.accountNumber(),
                request.contactPhone(), request.contactEmail());

        return new ReceivingAccount(id, request.bankName(), request.beneficiaryName(),
                request.accountNumber(), request.contactPhone(), request.contactEmail());
    }

    // ==================== DTO ====================

    public record ReceivingAccount(
            UUID id,
            String bankName,
            String beneficiaryName,
            String accountNumber,
            String contactPhone,
            String contactEmail
    ) {}

    public record SaveReceivingAccountRequest(
            @NotBlank String bankName,
            @NotBlank String beneficiaryName,
            @NotBlank String accountNumber,
            String contactPhone,
            String contactEmail
    ) {}
}
