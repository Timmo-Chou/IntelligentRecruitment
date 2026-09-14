package com.intelligentrecruitment.billing.application;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.boss.application.BossRequestContext;
import com.intelligentrecruitment.shared.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BOSS billing adapter. Recruitment never creates or mutates a local account or ledger. */
@Service
public class BillingService {
    private final BossControlPlaneClient boss;
    private final BossUsageIdempotencyStore usageStore;

    public BillingService(BossControlPlaneClient boss, BossUsageIdempotencyStore usageStore) {
        this.boss = boss;
        this.usageStore = usageStore;
    }

    /** Reads the BOSS wallet through the user's BOSS session. */
    @Transactional(readOnly = true)
    public BillingView view(UUID userId, UUID companyId) {
        var wallet = boss.companyWallet(BossRequestContext.accessToken(userId), companyId);
        return new BillingView(companyId, wallet.path("currency").asText("CNY"),
                wallet.path("gift_micro").asLong(0), wallet.path("recharge_micro").asLong(0),
                wallet.path("reserved_gift_micro").asLong(0), wallet.path("reserved_recharge_micro").asLong(0),
                true, List.of(), List.of(), 0);
    }

    /** Checks BOSS capability/price and reserves against the BOSS wallet. */
    @Transactional
    public ReservationView reserve(UUID userId, UUID companyId, String businessReference, long amountMicro) {
        String reference = requiredReference(businessReference);
        if (amountMicro < 0) throw new ApiException("INVALID_AMOUNT", "冻结金额不能小于0", HttpStatus.BAD_REQUEST);
        String capability = capabilityFor(reference);
        if (!boss.quotaCheck(companyId, capabilityFor(reference))) {
            throw new ApiException("CAPABILITY_NOT_ENABLED", "BOSS 未开通该 AI 能力", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        long unitPriceMicro = quoteUnitPrice(companyId, capability);
        long requestedUnits = unitPriceMicro <= 0 ? 1 : Math.max(1, divideCeil(amountMicro, unitPriceMicro));
        boss.reserve(companyId, capability, reference, requestedUnits, amountMicro);
        return reservation(companyId, reference, "RESERVED", amountMicro, 0);
    }

    public ReservationView settle(UUID userId, UUID companyId, String businessReference, long actualAmountMinor) {
        return settleSystem(companyId, businessReference, actualAmountMinor);
    }

    /** Worker completion path; only BOSS machine credentials are used. */
    public ReservationView settleSystem(UUID companyId, String businessReference, long actualAmountMinor) {
        return settleSystemWithUnits(companyId, businessReference, actualAmountMinor, actualAmountMinor > 0 ? 1 : 0);
    }

    public ReservationView settleSystemWithUnits(UUID companyId, String businessReference,
                                                  long actualAmountMinor, long successfulUnits) {
        String reference = requiredReference(businessReference);
        if (actualAmountMinor < 0 || successfulUnits < 0) {
            throw new ApiException("INVALID_AMOUNT", "结算金额不能小于0", HttpStatus.BAD_REQUEST);
        }
        Instant occurredAt = usageStore.timestamp(reference);
        boss.reportUsage(reference, reference, companyId, capabilityFor(reference),
                successfulUnits > 0 ? "SUCCEEDED" : "FAILED", successfulUnits, 0, 0, occurredAt);
        return reservation(companyId, reference, successfulUnits > 0 ? "SETTLED" : "RELEASED",
                actualAmountMinor, actualAmountMinor);
    }

    public long quoteUnitPrice(UUID companyId, String capability) {
        long price = boss.quoteUnitPrice(companyId, capability);
        if (price < 0) throw new ApiException("ACTIVE_PRICE_NOT_FOUND", "BOSS 未配置生效价格", HttpStatus.UNPROCESSABLE_ENTITY);
        return price;
    }

    private static ReservationView reservation(UUID companyId, String reference, String status,
                                               long reserved, long settled) {
        UUID id = UUID.nameUUIDFromBytes(("boss:" + companyId + ":" + reference).getBytes(StandardCharsets.UTF_8));
        return new ReservationView(id, status, reserved, settled, Math.max(0, reserved - settled));
    }

    private static String capabilityFor(String reference) {
        if (reference.startsWith("jd-run:")) return "JD_GENERATION";
        if (reference.startsWith("resume-parse:")) return "RESUME_PARSE";
        if (reference.startsWith("screening-run:")) return "RESUME_SCREENING";
        return "GENERAL_CHAT";
    }

    private static String requiredReference(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new ApiException("INVALID_BUSINESS_REFERENCE", "业务引用不能为空且不能超过160字符", HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    private static long divideCeil(long value, long divisor) { return value == 0 ? 0 : ((value - 1) / divisor) + 1; }

    public record BillingView(UUID workspaceId, String currency, long giftAmountMicro,
                              long rechargeAmountMicro, long reservedGiftAmountMicro, long reservedRechargeAmountMicro, boolean canViewLedger,
                              List<Object> creditLots, List<Object> ledger, long todaySpentAmountMinor) {
        public long availableAmountMicro() { return giftAmountMicro + rechargeAmountMicro; }
        /** Temporary source-compatible alias; values are micro-CNY, not cents. */
        @Deprecated public long availableAmountMinor() { return availableAmountMicro(); }
        public long reservedAmountMicro() { return reservedGiftAmountMicro + reservedRechargeAmountMicro; }
    }
    public record ReservationView(UUID id, String status, long reservedAmountMinor,
                                  long settledAmountMinor, long releasedAmountMinor) { }
}
