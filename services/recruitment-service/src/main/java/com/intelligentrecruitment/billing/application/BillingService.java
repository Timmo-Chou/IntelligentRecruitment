package com.intelligentrecruitment.billing.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class BillingService {
    private final JdbcTemplate jdbc;

    public BillingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID createAccount(UUID workspaceId) {
        UUID accountId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO billing_accounts
                (id, workspace_id, currency, available_amount_minor, reserved_amount_minor, status, created_at, updated_at)
                VALUES (?, ?, 'CNY', 0, 0, 'ACTIVE', ?, ?)
                """, accountId, workspaceId, timestamp(now), timestamp(now));
        return accountId;
    }

    @Transactional
    public boolean grantTrial(String subjectType, UUID subjectId, String policyCode, UUID workspaceId,
                              long amountMinor, UUID operatorUserId) {
        UUID eligibilityId = UUID.randomUUID();
        Instant now = Instant.now();
        int inserted = jdbc.update("""
                INSERT INTO trial_eligibilities (id, subject_type, subject_id, policy_code, granted_at, workspace_id)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (subject_type, subject_id, policy_code) DO NOTHING
                """, eligibilityId, subjectType, subjectId, policyCode, timestamp(now), workspaceId);
        if (inserted == 0) return false;

        UUID accountId = jdbc.queryForObject("SELECT id FROM billing_accounts WHERE workspace_id = ?", UUID.class, workspaceId);
        UUID lotId = UUID.randomUUID();
        Instant expiresAt = now.plus(90, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO credit_lots
                (id, billing_account_id, source_type, original_amount_minor, available_amount_minor, issued_at, expires_at, status)
                VALUES (?, ?, 'TRIAL', ?, ?, ?, ?, 'ACTIVE')
                """, lotId, accountId, amountMinor, amountMinor, timestamp(now), timestamp(expiresAt));
        jdbc.update("""
                INSERT INTO billing_ledger_entries
                (id, billing_account_id, workspace_id, credit_lot_id, entry_type, amount_minor,
                 business_reference, idempotency_key, operator_user_id, reason, created_at)
                VALUES (?, ?, ?, ?, 'GRANT', ?, ?, ?, ?, 'Phase 2 trial credit', ?)
                """, UUID.randomUUID(), accountId, workspaceId, lotId, amountMinor,
                subjectType + ":" + subjectId, "trial:" + policyCode + ":" + subjectId, operatorUserId,
                timestamp(now));
        jdbc.update("""
                UPDATE billing_accounts SET available_amount_minor = available_amount_minor + ?,
                version = version + 1, updated_at = ? WHERE id = ?
                """, amountMinor, timestamp(now), accountId);
        return true;
    }

    @Transactional
    public BillingView view(UUID userId, UUID workspaceId) {
        String role = workspaceRole(userId, workspaceId);
        lockAccount(workspaceId);
        expireAvailableLots(workspaceId);
        BillingSummary summary = jdbc.queryForObject("""
                SELECT id, currency, available_amount_minor, reserved_amount_minor
                FROM billing_accounts WHERE workspace_id = ? AND status = 'ACTIVE'
                """, (rs, n) -> new BillingSummary(rs.getObject("id", UUID.class), rs.getString("currency"),
                        rs.getLong("available_amount_minor"), rs.getLong("reserved_amount_minor")), workspaceId);
        List<CreditLotView> lots = jdbc.query("""
                SELECT id, source_type, original_amount_minor, available_amount_minor, issued_at, expires_at, status
                FROM credit_lots WHERE billing_account_id = ? ORDER BY expires_at, issued_at
                """, (rs, n) -> new CreditLotView(rs.getObject("id", UUID.class), rs.getString("source_type"),
                        rs.getLong("original_amount_minor"), rs.getLong("available_amount_minor"),
                        rs.getTimestamp("issued_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status")), summary.accountId());
        boolean canViewLedger = List.of("WORKSPACE_OWNER", "WORKSPACE_ADMIN").contains(role);
        List<LedgerView> ledger = canViewLedger ? jdbc.query("""
                SELECT id, entry_type, amount_minor, business_reference, reason, created_at
                FROM billing_ledger_entries WHERE billing_account_id = ? ORDER BY created_at DESC LIMIT 100
                """, (rs, n) -> new LedgerView(rs.getObject("id", UUID.class), rs.getString("entry_type"),
                        rs.getLong("amount_minor"), rs.getString("business_reference"), rs.getString("reason"),
                        rs.getTimestamp("created_at").toInstant()), summary.accountId()) : List.of();
        // 今日花费合计（以 Asia/Shanghai 时区自然日为界，只统计支出类条目：SETTLEMENT 和金额 < 0 的 ADJUSTMENT）
        long todaySpentAmountMinor;
        try {
            java.time.ZoneId shanghai = java.time.ZoneId.of("Asia/Shanghai");
            java.time.LocalDate today = java.time.LocalDate.now(shanghai);
            java.time.Instant dayStart = today.atStartOfDay(shanghai).toInstant();
            java.time.Instant dayEnd = today.plusDays(1).atStartOfDay(shanghai).toInstant();
            Long spent = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(ABS(amount_minor)),0) FROM billing_ledger_entries
                    WHERE billing_account_id = ? AND created_at >= ? AND created_at < ?
                      AND (entry_type = 'SETTLEMENT' OR (entry_type = 'ADJUSTMENT' AND amount_minor < 0))
                    """, Long.class, summary.accountId(), java.sql.Timestamp.from(dayStart), java.sql.Timestamp.from(dayEnd));
            todaySpentAmountMinor = spent == null ? 0L : spent;
        } catch (Exception ignore) {
            todaySpentAmountMinor = 0L;
        }
        return new BillingView(workspaceId, summary.currency(), summary.availableAmountMinor(),
                summary.reservedAmountMinor(), canViewLedger, lots, ledger, todaySpentAmountMinor);
    }

    @Transactional
    public ReservationView reserve(UUID userId, UUID workspaceId, String businessReference, long amountMinor) {
        requireWorkspaceMembership(userId, workspaceId);
        businessReference = requiredReference(businessReference);
        if (amountMinor <= 0) throw new ApiException("INVALID_AMOUNT", "冻结金额必须大于0", HttpStatus.BAD_REQUEST);
        lockAccount(workspaceId);
        expireAvailableLots(workspaceId);
        AccountRow account = lockAccount(workspaceId);
        List<ReservationView> existing = jdbc.query("""
                SELECT id, status, reserved_amount_minor, settled_amount_minor, released_amount_minor
                FROM billing_reservations WHERE billing_account_id=? AND business_reference=?
                """, (rs,n)->reservation(rs), account.id(), businessReference);
        if (!existing.isEmpty()) {
            if (existing.getFirst().reservedAmountMinor() != amountMinor) {
                throw new ApiException("IDEMPOTENCY_CONFLICT", "相同业务引用的冻结金额不一致", HttpStatus.CONFLICT);
            }
            return existing.getFirst();
        }
        if (account.availableAmountMinor() < amountMinor) {
            throw new ApiException("INSUFFICIENT_BALANCE", "可用额度不足", HttpStatus.PAYMENT_REQUIRED);
        }
        List<LotRow> lots = jdbc.query("""
                SELECT id, available_amount_minor, expires_at FROM credit_lots
                WHERE billing_account_id=? AND status='ACTIVE' AND available_amount_minor>0 AND expires_at>?
                ORDER BY expires_at, issued_at FOR UPDATE
                """, (rs,n)->new LotRow(rs.getObject("id",UUID.class),rs.getLong("available_amount_minor"),
                rs.getTimestamp("expires_at").toInstant()), account.id(), timestamp(Instant.now()));
        UUID reservationId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO billing_reservations
                (id,billing_account_id,workspace_id,business_reference,reserved_amount_minor,status,created_by,created_at)
                VALUES (?,?,?,?,?,'RESERVED',?,?)
                """, reservationId, account.id(), workspaceId, requiredReference(businessReference), amountMinor,
                userId, timestamp(now));
        long remaining = amountMinor;
        for (LotRow lot : lots) {
            long allocated = Math.min(remaining, lot.availableAmountMinor());
            if (allocated == 0) continue;
            jdbc.update("UPDATE credit_lots SET available_amount_minor=available_amount_minor-? WHERE id=?", allocated, lot.id());
            jdbc.update("""
                    INSERT INTO billing_reservation_allocations
                    (id,reservation_id,credit_lot_id,reserved_amount_minor) VALUES (?,?,?,?)
                    """, UUID.randomUUID(), reservationId, lot.id(), allocated);
            remaining -= allocated;
            if (remaining == 0) break;
        }
        if (remaining != 0) throw new ApiException("BALANCE_RECONCILIATION_FAILED", "额度批次与余额不一致", HttpStatus.CONFLICT);
        jdbc.update("""
                UPDATE billing_accounts SET available_amount_minor=available_amount_minor-?,
                reserved_amount_minor=reserved_amount_minor+?,version=version+1,updated_at=? WHERE id=?
                """, amountMinor, amountMinor, timestamp(now), account.id());
        ledger(account.id(), workspaceId, null, "RESERVE", -amountMinor, businessReference,
                "reserve:"+businessReference, userId, "Billable task reservation", now);
        return new ReservationView(reservationId,"RESERVED",amountMinor,0,0);
    }

    @Transactional
    public ReservationView settle(UUID userId, UUID workspaceId, String businessReference, long actualAmountMinor) {
        requireWorkspaceMembership(userId, workspaceId);
        return settleInternal(workspaceId,businessReference,actualAmountMinor);
    }

    @Transactional
    public ReservationView settleSystem(UUID workspaceId,String businessReference,long actualAmountMinor){
        return settleInternal(workspaceId,businessReference,actualAmountMinor);
    }

    private ReservationView settleInternal(UUID workspaceId, String businessReference, long actualAmountMinor) {
        businessReference = requiredReference(businessReference);
        if (actualAmountMinor < 0) throw new ApiException("INVALID_AMOUNT", "结算金额不能小于0", HttpStatus.BAD_REQUEST);
        AccountRow account = lockAccount(workspaceId);
        List<ReservationRow> rows = jdbc.query("""
                SELECT id,status,reserved_amount_minor,settled_amount_minor,released_amount_minor
                FROM billing_reservations WHERE billing_account_id=? AND business_reference=? FOR UPDATE
                """, (rs,n)->new ReservationRow(rs.getObject("id",UUID.class),rs.getString("status"),
                rs.getLong("reserved_amount_minor"),rs.getLong("settled_amount_minor"),rs.getLong("released_amount_minor")),
                account.id(), businessReference);
        if (rows.isEmpty()) throw new ApiException("RESERVATION_NOT_FOUND", "额度冻结记录不存在", HttpStatus.NOT_FOUND);
        ReservationRow reservation = rows.getFirst();
        if (!reservation.status().equals("RESERVED")) {
            if (reservation.settledAmountMinor() != actualAmountMinor) {
                throw new ApiException("IDEMPOTENCY_CONFLICT", "相同业务引用的结算金额不一致", HttpStatus.CONFLICT);
            }
            return reservation.view();
        }
        if (actualAmountMinor > reservation.reservedAmountMinor()) {
            throw new ApiException("SETTLEMENT_EXCEEDS_RESERVATION", "结算金额不能超过冻结金额", HttpStatus.CONFLICT);
        }
        List<AllocationRow> allocations = jdbc.query("""
                SELECT a.id,a.credit_lot_id,a.reserved_amount_minor,l.expires_at,l.status
                FROM billing_reservation_allocations a JOIN credit_lots l ON l.id=a.credit_lot_id
                WHERE a.reservation_id=? ORDER BY l.expires_at FOR UPDATE
                """, (rs,n)->new AllocationRow(rs.getObject("id",UUID.class),rs.getObject("credit_lot_id",UUID.class),
                rs.getLong("reserved_amount_minor"),rs.getTimestamp("expires_at").toInstant(),rs.getString("status")), reservation.id());
        long remainingSettlement = actualAmountMinor;
        long releasable = 0;
        long expiredRelease = 0;
        Instant now = Instant.now();
        for (AllocationRow allocation : allocations) {
            long settled = Math.min(remainingSettlement, allocation.reservedAmountMinor());
            long released = allocation.reservedAmountMinor() - settled;
            remainingSettlement -= settled;
            jdbc.update("UPDATE billing_reservation_allocations SET settled_amount_minor=?,released_amount_minor=? WHERE id=?",
                    settled,released,allocation.id());
            if (released > 0 && allocation.expiresAt().isAfter(now) && allocation.status().equals("ACTIVE")) {
                jdbc.update("UPDATE credit_lots SET available_amount_minor=available_amount_minor+? WHERE id=?",released,allocation.lotId());
                releasable += released;
            } else {
                expiredRelease += released;
            }
        }
        long releasedTotal = reservation.reservedAmountMinor()-actualAmountMinor;
        String status = actualAmountMinor==0?"RELEASED":(releasedTotal==0?"SETTLED":"PARTIALLY_SETTLED");
        jdbc.update("""
                UPDATE billing_reservations SET status=?,settled_amount_minor=?,released_amount_minor=?,completed_at=? WHERE id=?
                """,status,actualAmountMinor,releasedTotal,timestamp(now),reservation.id());
        jdbc.update("""
                UPDATE billing_accounts SET reserved_amount_minor=reserved_amount_minor-?,
                available_amount_minor=available_amount_minor+?,version=version+1,updated_at=? WHERE id=?
                """,reservation.reservedAmountMinor(),releasable,timestamp(now),account.id());
        if(actualAmountMinor>0) ledger(account.id(),workspaceId,null,"SETTLEMENT",-actualAmountMinor,businessReference,
                "settle:"+businessReference,null,"Billable task settlement",now);
        if(releasable>0) ledger(account.id(),workspaceId,null,"RELEASE",releasable,businessReference,
                "release:"+businessReference,null,"Unused reservation released",now);
        if(expiredRelease>0) ledger(account.id(),workspaceId,null,"EXPIRE",-expiredRelease,businessReference,
                "expired-release:"+businessReference,null,"Released credit had already expired",now);
        return new ReservationView(reservation.id(),status,reservation.reservedAmountMinor(),actualAmountMinor,releasedTotal);
    }

    /**
     * 平台管理员视角查询账户余额和额度批次（不含成员校验）。
     */
    public AdminBillingView viewForAdmin(UUID workspaceId) {
        AdminAccountRow account = jdbc.query(
                "SELECT id, currency, available_amount_minor, reserved_amount_minor FROM billing_accounts WHERE workspace_id = ? AND status = 'ACTIVE'",
                (rs, n) -> new AdminAccountRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("currency"),
                        rs.getLong("available_amount_minor"),
                        rs.getLong("reserved_amount_minor")),
                workspaceId)
                .stream().findFirst().orElseThrow(
                        () -> new ApiException("BILLING_ACCOUNT_NOT_FOUND", "账本账户不存在", HttpStatus.NOT_FOUND));

        List<AdminCreditLotRow> lots = jdbc.query("""
                SELECT id, source_type, original_amount_minor, available_amount_minor, expires_at, status
                FROM credit_lots WHERE billing_account_id = ? AND status = 'ACTIVE'
                ORDER BY expires_at
                """, (rs, n) -> new AdminCreditLotRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("source_type"),
                        rs.getLong("original_amount_minor"),
                        rs.getLong("available_amount_minor"),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status")),
                account.id());

        return new AdminBillingView(account.currency(), account.availableAmountMinor(),
                account.reservedAmountMinor(), lots);
    }

    /**
     * 平台管理员视角查询某工作空间的账本条目（分页）。
     * 仅查询账本流水，不修改余额，不加 workspace 成员校验。
     */
    @Transactional(readOnly = true)
    public PagedLedgerEntries listLedgerEntries(UUID workspaceId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;

        // 不加行锁的简单查询，避免与写事务冲突
        UUID accountId = jdbc.query(
                "SELECT id FROM billing_accounts WHERE workspace_id = ? AND status = 'ACTIVE'",
                (rs, n) -> rs.getObject("id", UUID.class), workspaceId)
                .stream().findFirst().orElseThrow(
                        () -> new ApiException("BILLING_ACCOUNT_NOT_FOUND", "账本账户不存在", HttpStatus.NOT_FOUND));

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM billing_ledger_entries WHERE billing_account_id = ?",
                Long.class, accountId);

        List<LedgerEntryRow> items = jdbc.query("""
                SELECT l.id, l.entry_type, l.amount_minor, l.business_reference, l.reason, l.created_at,
                       l.operator_user_id, u.display_name AS operator_name
                FROM billing_ledger_entries l
                LEFT JOIN users u ON u.id = l.operator_user_id
                WHERE l.billing_account_id = ?
                ORDER BY l.created_at DESC
                LIMIT ? OFFSET ?
                """, (rs, n) -> new LedgerEntryRow(
                rs.getObject("id", UUID.class),
                rs.getString("entry_type"),
                rs.getLong("amount_minor"),
                rs.getString("business_reference"),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("operator_name")),
                accountId, pageSize, offset);

        return new PagedLedgerEntries(items, total != null ? total : 0, page, pageSize);
    }

    @Transactional
    public void adjust(UUID workspaceId, long amountMinor, String reference, String reason) {
        if(amountMinor==0) throw new ApiException("INVALID_AMOUNT","调整金额不能为0",HttpStatus.BAD_REQUEST);
        reference=requiredReference(reference);
        lockAccount(workspaceId);
        expireAvailableLots(workspaceId);
        AccountRow account=lockAccount(workspaceId);
        List<Long> existing=jdbc.query("SELECT amount_minor FROM billing_ledger_entries WHERE billing_account_id=? AND idempotency_key=?",(rs,n)->rs.getLong("amount_minor"),account.id(),"adjust:"+reference);
        if(!existing.isEmpty()){if(existing.getFirst()!=amountMinor)throw new ApiException("IDEMPOTENCY_CONFLICT","相同调整引用的金额不一致",HttpStatus.CONFLICT);return;}
        Instant now=Instant.now();
        if(amountMinor>0){
            UUID lotId=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO credit_lots
                    (id,billing_account_id,source_type,original_amount_minor,available_amount_minor,issued_at,expires_at,status)
                    VALUES (?,?,'MANUAL_ADJUSTMENT',?,?,?,?, 'ACTIVE')
                    """,lotId,account.id(),amountMinor,amountMinor,timestamp(now),
                    timestamp(now.plus(90,ChronoUnit.DAYS)));
        }else{
            long deduction=-amountMinor;
            if(account.availableAmountMinor()<deduction) throw new ApiException("INSUFFICIENT_BALANCE","调整后的余额不能为负",HttpStatus.CONFLICT);
            List<LotRow> lots=jdbc.query("""
                    SELECT id,available_amount_minor,expires_at FROM credit_lots
                    WHERE billing_account_id=? AND status='ACTIVE' AND available_amount_minor>0 AND expires_at>?
                    ORDER BY expires_at,issued_at FOR UPDATE
                    """,(rs,n)->new LotRow(rs.getObject("id",UUID.class),rs.getLong("available_amount_minor"),rs.getTimestamp("expires_at").toInstant()),account.id(),timestamp(now));
            for(LotRow lot:lots){long used=Math.min(deduction,lot.availableAmountMinor());jdbc.update("UPDATE credit_lots SET available_amount_minor=available_amount_minor-? WHERE id=?",used,lot.id());deduction-=used;if(deduction==0)break;}
        }
        jdbc.update("UPDATE billing_accounts SET available_amount_minor=available_amount_minor+?,version=version+1,updated_at=? WHERE id=?",amountMinor,timestamp(now),account.id());
        ledger(account.id(),workspaceId,null,"ADJUSTMENT",amountMinor,requiredReference(reference),"adjust:"+reference,null,reason,now);
    }

    private void expireAvailableLots(UUID workspaceId){
        UUID accountId=jdbc.queryForObject("SELECT id FROM billing_accounts WHERE workspace_id=?",UUID.class,workspaceId);
        List<ExpiredLot> expired=jdbc.query("""
                SELECT id,available_amount_minor FROM credit_lots
                WHERE billing_account_id=? AND status='ACTIVE' AND expires_at<=? AND available_amount_minor>0 FOR UPDATE
                """,(rs,n)->new ExpiredLot(rs.getObject("id",UUID.class),rs.getLong("available_amount_minor")),accountId,timestamp(Instant.now()));
        for(ExpiredLot lot:expired){Instant now=Instant.now();jdbc.update("UPDATE credit_lots SET available_amount_minor=0,status='EXPIRED' WHERE id=?",lot.id());jdbc.update("UPDATE billing_accounts SET available_amount_minor=available_amount_minor-?,version=version+1,updated_at=? WHERE id=?",lot.amount(),timestamp(now),accountId);ledger(accountId,workspaceId,lot.id(),"EXPIRE",-lot.amount(),"credit-lot:"+lot.id(),"expire:"+lot.id(),null,"Credit lot expired",now);}
    }

    private AccountRow lockAccount(UUID workspaceId){
        List<AccountRow> rows=jdbc.query("SELECT id,available_amount_minor,reserved_amount_minor FROM billing_accounts WHERE workspace_id=? AND status='ACTIVE' FOR UPDATE",(rs,n)->new AccountRow(rs.getObject("id",UUID.class),rs.getLong("available_amount_minor"),rs.getLong("reserved_amount_minor")),workspaceId);
        if(rows.isEmpty())throw new ApiException("BILLING_ACCOUNT_NOT_FOUND","账本账户不存在",HttpStatus.NOT_FOUND);return rows.getFirst();
    }

    private void ledger(UUID accountId,UUID workspaceId,UUID lotId,String type,long amount,String reference,String key,UUID operator,String reason,Instant now){jdbc.update("""
            INSERT INTO billing_ledger_entries
            (id,billing_account_id,workspace_id,credit_lot_id,entry_type,amount_minor,business_reference,idempotency_key,operator_user_id,reason,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,UUID.randomUUID(),accountId,workspaceId,lotId,type,amount,reference,key,operator,reason,timestamp(now));}

    private static String requiredReference(String value){if(value==null||value.isBlank()||value.length()>160)throw new ApiException("INVALID_BUSINESS_REFERENCE","业务引用不能为空且不能超过160字符",HttpStatus.BAD_REQUEST);return value.trim();}

    private static ReservationView reservation(java.sql.ResultSet rs)throws java.sql.SQLException{return new ReservationView(rs.getObject("id",UUID.class),rs.getString("status"),rs.getLong("reserved_amount_minor"),rs.getLong("settled_amount_minor"),rs.getLong("released_amount_minor"));}

    private void requireWorkspaceMembership(UUID userId, UUID workspaceId) {
        workspaceRole(userId, workspaceId);
    }

    private String workspaceRole(UUID userId,UUID workspaceId){
        List<String> roles=jdbc.query("SELECT role FROM workspace_memberships WHERE workspace_id=? AND user_id=? AND status='ACTIVE'",(rs,n)->rs.getString("role"),workspaceId,userId);
        if(roles.isEmpty()){
            throw new ApiException("WORKSPACE_NOT_FOUND", "工作空间不存在或无权访问", HttpStatus.NOT_FOUND);
        }
        return roles.getFirst();
    }

    private record BillingSummary(UUID accountId, String currency, long availableAmountMinor, long reservedAmountMinor) {}
    public record CreditLotView(UUID id, String sourceType, long originalAmountMinor, long availableAmountMinor,
                                Instant issuedAt, Instant expiresAt, String status) {}
    public record LedgerView(UUID id, String entryType, long amountMinor, String businessReference,
                             String reason, Instant createdAt) {}
    public record BillingView(UUID workspaceId, String currency, long availableAmountMinor, long reservedAmountMinor,
                              boolean canViewLedger, List<CreditLotView> creditLots, List<LedgerView> ledger,
                              long todaySpentAmountMinor) {}
    public record ReservationView(UUID id,String status,long reservedAmountMinor,long settledAmountMinor,long releasedAmountMinor){}
    private record AccountRow(UUID id,long availableAmountMinor,long reservedAmountMinor){}
    private record LotRow(UUID id,long availableAmountMinor,Instant expiresAt){}
    private record ExpiredLot(UUID id,long amount){}
    private record AllocationRow(UUID id,UUID lotId,long reservedAmountMinor,Instant expiresAt,String status){}
    private record ReservationRow(UUID id,String status,long reservedAmountMinor,long settledAmountMinor,long releasedAmountMinor){ReservationView view(){return new ReservationView(id,status,reservedAmountMinor,settledAmountMinor,releasedAmountMinor);}}

    public record LedgerEntryRow(UUID id, String entryType, long amountMinor, String businessReference,
                                 String reason, Instant createdAt, String operatorName) {}
    public record PagedLedgerEntries(List<LedgerEntryRow> items, long total, int page, int pageSize) {}

    // 管理员视角的账户余额视图
    public record AdminCreditLotRow(UUID id, String sourceType, long originalAmountMinor,
                                    long availableAmountMinor, Instant expiresAt, String status) {}
    public record AdminBillingView(String currency, long availableAmountMinor, long reservedAmountMinor,
                                   List<AdminCreditLotRow> creditLots) {}
    private record AdminAccountRow(UUID id, String currency, long availableAmountMinor, long reservedAmountMinor) {}
}
