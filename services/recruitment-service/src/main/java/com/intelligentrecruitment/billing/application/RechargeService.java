package com.intelligentrecruitment.billing.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

/** 用户充值订单、支付宝页面支付签名和异步通知验签。 */
@Service
public class RechargeService {
    private static final long MIN_AMOUNT_MINOR = 1_000L;
    private static final long MAX_AMOUNT_MINOR = 500_000L;
    private final JdbcTemplate jdbc;
    private final BillingService billing;
    private final String alipayGatewayUrl;
    private final String alipayAppId;
    private final String alipayPrivateKey;
    private final String alipayPublicKey;
    private final String notifyUrl;
    private final String returnUrl;
    private final int creditValidityDays;

    public RechargeService(JdbcTemplate jdbc, BillingService billing,
                           @Value("${app.recharge.alipay.gateway-url:https://openapi.alipay.com/gateway.do}") String alipayGatewayUrl,
                           @Value("${app.recharge.alipay.app-id:}") String alipayAppId,
                           @Value("${app.recharge.alipay.private-key:}") String alipayPrivateKey,
                           @Value("${app.recharge.alipay.public-key:}") String alipayPublicKey,
                           @Value("${app.recharge.alipay.notify-url:}") String notifyUrl,
                           @Value("${app.recharge.alipay.return-url:}") String returnUrl,
                           @Value("${app.recharge.credit-validity-days:3650}") int creditValidityDays) {
        this.jdbc = jdbc; this.billing = billing; this.alipayGatewayUrl = alipayGatewayUrl;
        this.alipayAppId = alipayAppId; this.alipayPrivateKey = alipayPrivateKey;
        this.alipayPublicKey = alipayPublicKey; this.notifyUrl = notifyUrl; this.returnUrl = returnUrl;
        this.creditValidityDays = creditValidityDays;
    }

    @Transactional(readOnly = true)
    public RechargeContext context(UUID userId, UUID workspaceId) {
        WorkspaceRow workspace = workspaceForUser(userId, workspaceId);
        ReceivingAccount account = activeReceivingAccount();
        return new RechargeContext(workspace.id(), workspace.name(), workspace.defaultPayerName(), account);
    }

    @Transactional
    public OnlineOrder createAlipayOrder(UUID userId, UUID workspaceId, long amountMinor, String payerName) {
        WorkspaceRow workspace = workspaceForUser(userId, workspaceId);
        validateAmount(amountMinor);
        if (!alipayConfigured()) throw new ApiException("ALIPAY_NOT_CONFIGURED", "支付宝商户配置未完成，暂时无法发起在线充值", HttpStatus.SERVICE_UNAVAILABLE);
        String normalizedPayer = requiredPayerName(payerName, workspace.defaultPayerName());
        UUID accountId = jdbc.queryForObject("SELECT id FROM billing_accounts WHERE workspace_id=? AND status='ACTIVE'", UUID.class, workspaceId);
        Instant now = Instant.now();
        String orderNo = "IFX" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC).format(now)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO recharge_orders (id,order_no,billing_account_id,workspace_id,created_by,payer_name,payment_method,amount_minor,status,created_at,updated_at)
                VALUES (?,?,?,?,?,?, 'ALIPAY', ?, 'PENDING', ?, ?)
                """, UUID.randomUUID(), orderNo, accountId, workspaceId, userId, normalizedPayer, amountMinor, timestamp(now), timestamp(now));
        return new OnlineOrder(orderNo, amountMinor, alipayPageUrl(orderNo, amountMinor, workspace.name()), "PENDING");
    }

    @Transactional(readOnly = true)
    public OrderStatus orderStatus(UUID userId, UUID workspaceId, String orderNo) {
        workspaceForUser(userId, workspaceId);
        return jdbc.query("SELECT order_no,amount_minor,status,paid_at FROM recharge_orders WHERE workspace_id=? AND order_no=?",
                (rs, n) -> new OrderStatus(rs.getString("order_no"), rs.getLong("amount_minor"), rs.getString("status"),
                        rs.getTimestamp("paid_at") == null ? null : rs.getTimestamp("paid_at").toInstant()), workspaceId, orderNo)
                .stream().findFirst().orElseThrow(() -> new ApiException("RECHARGE_ORDER_NOT_FOUND", "充值订单不存在", HttpStatus.NOT_FOUND));
    }

    @Transactional
    public void handleAlipayNotification(Map<String, String> payload) {
        if (!alipayConfigured() || !verifyAlipaySignature(payload)) throw new ApiException("ALIPAY_SIGNATURE_INVALID", "支付宝通知验签失败", HttpStatus.BAD_REQUEST);
        String orderNo = payload.get("out_trade_no");
        String tradeStatus = payload.get("trade_status");
        if (orderNo == null || !("TRADE_SUCCESS".equals(tradeStatus) || "TRADE_FINISHED".equals(tradeStatus))) return;
        OrderRow order = jdbc.query("SELECT id,workspace_id,created_by,amount_minor,status FROM recharge_orders WHERE order_no=? FOR UPDATE",
                (rs, n) -> new OrderRow(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getObject("created_by", UUID.class), rs.getLong("amount_minor"), rs.getString("status")), orderNo)
                .stream().findFirst().orElseThrow(() -> new ApiException("RECHARGE_ORDER_NOT_FOUND", "充值订单不存在", HttpStatus.NOT_FOUND));
        if ("PAID".equals(order.status())) return;
        if (!"PENDING".equals(order.status()) || order.amountMinor() != yuanToMinor(payload.get("total_amount"))) {
            throw new ApiException("ALIPAY_ORDER_MISMATCH", "支付宝订单金额或状态不匹配", HttpStatus.CONFLICT);
        }
        Instant now = Instant.now();
        billing.grantRecharge(order.workspaceId(), order.amountMinor(), orderNo, order.createdBy(), creditValidityDays);
        jdbc.update("UPDATE recharge_orders SET status='PAID',provider_trade_no=?,paid_at=?,updated_at=? WHERE id=?",
                payload.get("trade_no"), timestamp(now), timestamp(now), order.id());
    }

    @Transactional(readOnly = true)
    public ReceivingAccount activeReceivingAccount() {
        return jdbc.query("""
                SELECT id,bank_name,beneficiary_name,account_number,contact_phone,contact_email
                FROM recharge_receiving_accounts WHERE status='ACTIVE'
                """, (rs, n) -> new ReceivingAccount(rs.getObject("id", UUID.class), rs.getString("bank_name"),
                rs.getString("beneficiary_name"), rs.getString("account_number"), rs.getString("contact_phone"), rs.getString("contact_email")))
                .stream().findFirst().orElse(null);
    }

    @Transactional
    public ReceivingAccount saveReceivingAccount(String bankName, String beneficiaryName, String accountNumber, String contactPhone, String contactEmail) {
        required(bankName, "开户银行"); required(beneficiaryName, "收款户名"); required(accountNumber, "收款账号");
        Instant now = Instant.now();
        jdbc.update("UPDATE recharge_receiving_accounts SET status='INACTIVE',updated_at=? WHERE status='ACTIVE'", timestamp(now));
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recharge_receiving_accounts (id,bank_name,beneficiary_name,account_number,contact_phone,contact_email,status,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'ACTIVE',?,?)
                """, id, bankName.trim(), beneficiaryName.trim(), accountNumber.trim(), blankToNull(contactPhone), blankToNull(contactEmail), timestamp(now), timestamp(now));
        return new ReceivingAccount(id, bankName.trim(), beneficiaryName.trim(), accountNumber.trim(), blankToNull(contactPhone), blankToNull(contactEmail));
    }

    private WorkspaceRow workspaceForUser(UUID userId, UUID workspaceId) {
        return jdbc.query("""
                SELECT w.id,w.name,COALESCE(c.display_name,u.display_name, CONCAT('个人账号 ', u.phone_last_four)) AS payer_name
                FROM workspaces w JOIN workspace_memberships m ON m.workspace_id=w.id
                JOIN users u ON u.id=? LEFT JOIN companies c ON c.id=w.company_id
                WHERE w.id=? AND m.user_id=? AND m.status='ACTIVE' AND w.status='ACTIVE'
                """, (rs, n) -> new WorkspaceRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("payer_name")), userId, workspaceId, userId)
                .stream().findFirst().orElseThrow(() -> new ApiException("WORKSPACE_NOT_FOUND", "工作空间不存在或无权访问", HttpStatus.NOT_FOUND));
    }

    private boolean alipayConfigured() { return !blank(alipayAppId) && !blank(alipayPrivateKey) && !blank(alipayPublicKey) && !blank(notifyUrl); }

    private String alipayPageUrl(String orderNo, long amountMinor, String workspaceName) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", alipayAppId); params.put("charset", "utf-8"); params.put("format", "JSON");
        params.put("method", "alipay.trade.page.pay"); params.put("notify_url", notifyUrl);
        params.put("sign_type", "RSA2"); params.put("timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai")).format(Instant.now()));
        params.put("version", "1.0");
        if (!blank(returnUrl)) params.put("return_url", returnUrl);
        params.put("biz_content", "{\"out_trade_no\":\"" + orderNo + "\",\"product_code\":\"FAST_INSTANT_TRADE_PAY\",\"total_amount\":\"" + String.format(java.util.Locale.ROOT, "%.2f", amountMinor / 100.0) + "\",\"subject\":\"iFoundX 工作空间充值 - " + jsonEscape(workspaceName) + "\"}");
        params.put("sign", sign(canonical(params), privateKey()));
        return alipayGatewayUrl + "?" + params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
    }

    private boolean verifyAlipaySignature(Map<String, String> payload) {
        String signature = payload.get("sign");
        if (blank(signature)) return false;
        Map<String, String> unsigned = new LinkedHashMap<>(payload); unsigned.remove("sign"); unsigned.remove("sign_type");
        try { Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(publicKey()); verifier.update(canonical(unsigned).getBytes(StandardCharsets.UTF_8)); return verifier.verify(Base64.getDecoder().decode(signature)); }
        catch (Exception ex) { return false; }
    }

    private static String canonical(Map<String, String> values) { return values.entrySet().stream().filter(e -> e.getValue() != null && !e.getValue().isBlank()).sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("&")); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String sign(String content, RSAPrivateKey key) { try { Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key); signer.update(content.getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(signer.sign()); } catch (Exception ex) { throw new ApiException("ALIPAY_SIGN_FAILED", "支付宝订单签名失败", HttpStatus.SERVICE_UNAVAILABLE); } }
    private RSAPrivateKey privateKey() { try { return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes(alipayPrivateKey))); } catch (Exception ex) { throw new ApiException("ALIPAY_PRIVATE_KEY_INVALID", "支付宝私钥配置无效", HttpStatus.SERVICE_UNAVAILABLE); } }
    private RSAPublicKey publicKey() { try { return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes(alipayPublicKey))); } catch (Exception ex) { throw new ApiException("ALIPAY_PUBLIC_KEY_INVALID", "支付宝公钥配置无效", HttpStatus.SERVICE_UNAVAILABLE); } }
    private static byte[] keyBytes(String value) { return Base64.getDecoder().decode(value.replaceAll("-----[^-]+-----", "").replaceAll("\\s", "")); }
    private static long yuanToMinor(String value) { try { return new java.math.BigDecimal(value).movePointRight(2).longValueExact(); } catch (Exception ex) { throw new ApiException("ALIPAY_AMOUNT_INVALID", "支付宝金额格式无效", HttpStatus.BAD_REQUEST); } }
    private static void validateAmount(long amountMinor) { if (amountMinor < MIN_AMOUNT_MINOR || amountMinor > MAX_AMOUNT_MINOR) throw new ApiException("RECHARGE_AMOUNT_OUT_OF_RANGE", "单次充值金额需在 ¥10 至 ¥5000 之间", HttpStatus.BAD_REQUEST); }
    private static String requiredPayerName(String supplied, String fallback) { String value = blank(supplied) ? fallback : supplied.trim(); required(value, "汇款户名"); if (value.length() > 200) throw new ApiException("PAYER_NAME_TOO_LONG", "汇款户名不能超过200个字符", HttpStatus.BAD_REQUEST); return value; }
    private static void required(String value, String label) { if (blank(value)) throw new ApiException("RECHARGE_ACCOUNT_INVALID", label + "不能为空", HttpStatus.BAD_REQUEST); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
    private static String jsonEscape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }

    public record ReceivingAccount(UUID id, String bankName, String beneficiaryName, String accountNumber, String contactPhone, String contactEmail) {}
    public record RechargeContext(UUID workspaceId, String workspaceName, String defaultPayerName, ReceivingAccount receivingAccount) {}
    public record OnlineOrder(String orderNo, long amountMinor, String paymentUrl, String status) {}
    public record OrderStatus(String orderNo, long amountMinor, String status, Instant paidAt) {}
    private record WorkspaceRow(UUID id, String name, String defaultPayerName) {}
    private record OrderRow(UUID id, UUID workspaceId, UUID createdBy, long amountMinor, String status) {}
}
