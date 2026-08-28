package com.intelligentrecruitment.aiplatform.assistant.api;

import com.intelligentrecruitment.aiplatform.assistant.application.AIAssistantService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * AI咨询助手控制器
 * 提供对话接口和配置查询接口
 */
@RestController
@RequestMapping("/api/v1")
public class AIAssistantController {

    private final AIAssistantService assistantService;

    public AIAssistantController(AIAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    /**
     * AI咨询助手对话接口
     * 处理用户消息，返回AI回复和下一步操作建议
     */
    @PostMapping("/ai-assistant/chat")
    public AIAssistantService.ChatResponse chat(
            Authentication authentication,
            @RequestBody AIAssistantService.ChatRequest request) {
        // 验证用户身份
        UUID userId = CurrentUser.id(authentication);
        // 获取用户显示名称（用于创建工单）
        String userName = authentication.getName();
        // 如果没有会话ID，为用户创建新会话
        String sessionId = request.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "user_" + userId + "_" + System.currentTimeMillis();
        }
        return assistantService.chat(
                new AIAssistantService.ChatRequest(
                        request.message(),
                        sessionId,
                        request.stage(),
                        request.context(),
                        userId,
                        userName
                )
        );
    }

    /**
     * 获取AI咨询助手配置
     * 包含帮助手册链接、合作二维码、联系电话等
     */
    @GetMapping("/ai-assistant/config")
    public AIAssistantService.ConfigResponse getConfig(Authentication authentication) {
        // 验证用户身份
        CurrentUser.id(authentication);
        return assistantService.getConfig();
    }
}
