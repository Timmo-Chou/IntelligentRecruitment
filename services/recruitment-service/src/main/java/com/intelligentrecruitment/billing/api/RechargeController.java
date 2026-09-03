package com.intelligentrecruitment.billing.api;

import com.intelligentrecruitment.billing.application.RechargeService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class RechargeController {
    private final RechargeService recharge;
    public RechargeController(RechargeService recharge) { this.recharge = recharge; }

    @GetMapping("/workspaces/{workspaceId}/recharge")
    RechargeService.RechargeContext context(@PathVariable UUID workspaceId, Authentication authentication) {
        return recharge.context(CurrentUser.id(authentication), workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/recharge/alipay-orders")
    RechargeService.OnlineOrder createAlipayOrder(@PathVariable UUID workspaceId, @Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        return recharge.createAlipayOrder(CurrentUser.id(authentication), workspaceId, request.amountMinor(), request.payerName());
    }

    @GetMapping("/workspaces/{workspaceId}/recharge/orders/{orderNo}")
    RechargeService.OrderStatus orderStatus(@PathVariable UUID workspaceId, @PathVariable String orderNo, Authentication authentication) {
        return recharge.orderStatus(CurrentUser.id(authentication), workspaceId, orderNo);
    }

    /** 支付宝服务器异步通知；只有验签与订单核验通过后才会入账。 */
    @PostMapping(value = "/payments/alipay/notify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    String alipayNotify(@RequestParam MultiValueMap<String, String> form) {
        Map<String, String> payload = new java.util.LinkedHashMap<>();
        form.forEach((key, values) -> payload.put(key, values.isEmpty() ? "" : values.getFirst()));
        recharge.handleAlipayNotification(payload);
        return "success";
    }

    public record CreateOrderRequest(@Positive long amountMinor, String payerName) {}
}
