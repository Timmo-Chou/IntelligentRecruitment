package com.intelligentrecruitment.billing.api;

import com.intelligentrecruitment.billing.application.PricingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端定价查询 API（公开，所有登录用户都能看价格）。
 * 路径：/api/v1/pricing
 * 前端调用此接口展示功能点单价。
 */
@RestController
@RequestMapping("/api/v1/pricing")
public class UserPricingController {

    private final PricingService pricingService;

    public UserPricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    /**
     * 返回所有 ACTIVE 的计费项。用户端 UI 展示各功能点单价时调用。
     */
    @GetMapping
    List<PricingService.PricingItemRow> list() {
        return pricingService.listActive();
    }
}
