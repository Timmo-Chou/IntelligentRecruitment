package com.intelligentrecruitment.aiplatform.assistant.application;

import com.intelligentrecruitment.agentflow.application.RecruitmentFlowCoordinator;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.ConversationAgentCommand;
import com.intelligentrecruitment.platform.ticket.application.TicketService;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import com.intelligentrecruitment.tenancy.application.TenantAccessService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI咨询助手服务
 * 管理对话会话、阶段流转和回复生成。
 * 所有 AI 对话回复均经 BOSS 授权后由 AIAgentPlatform 生成与结算。
 * 用户反馈会自动创建工单到平台工单系统。
 */
@Service
public class AIAssistantService {

    private final TicketService ticketService;
    private final AiPlatformClient aiPlatform;
    private final TenantAccessService tenantAccess;
    private final RecruitmentFlowCoordinator flowCoordinator;

    // ---- 配置：AI助手基本配置 ----
    @Value("${app.ai-assistant.help-manual-url:https://help.intelligentrecruitment.com}")
    private String helpManualUrl;

    @Value("${app.ai-assistant.cooperation-qr-code-url:}")
    private String cooperationQrCodeUrl;

    @Value("${app.ai-assistant.cooperation-phone:400-888-8888}")
    private String cooperationPhone;

    @Value("${app.ai-assistant.cooperation-email:support@intelligentrecruitment.com}")
    private String cooperationEmail;

    public AIAssistantService(TicketService ticketService, AiPlatformClient aiPlatform,
                              TenantAccessService tenantAccess, RecruitmentFlowCoordinator flowCoordinator) {
        this.ticketService = ticketService;
        this.aiPlatform = aiPlatform;
        this.tenantAccess = tenantAccess;
        this.flowCoordinator = flowCoordinator;
    }

    /**
     * 会话状态：每个用户会话的对话上下文
     */
    public record AssistantSession(
            String sessionId,
            AssistantStage stage,
            Map<String, Object> context
    ) {}

    /**
     * 对话阶段枚举
     */
    public enum AssistantStage {
        GREETING,          // 初始问候
        HELP_MAIN,         // 帮助主菜单
        HELP_QA,           // 帮助问答
        FEEDBACK_COLLECT,  // 收集反馈内容
        FEEDBACK_CONFIRM,  // 确认反馈内容
        FEEDBACK_CONTACT,  // 收集联系方式
        COOPERATION,       // 合作需求
        TICKET_CREATED     // 工单已创建
    }

    /**
     * 对话响应
     */
    public record ChatResponse(
            String reply,
            List<ChatAction> actions,
            List<ChatAttachment> attachments,
            AssistantStage nextStage,
            String sessionId
    ) {}

    /**
     * 快捷操作按钮
     */
    public record ChatAction(
            String id,
            String label,
            String payload,
            String variant // "primary" or "secondary"
    ) {}

    /**
     * 附件（链接、二维码等）
     */
    public record ChatAttachment(
            String type,   // "link", "qr_code", "image"
            String url,
            String text,
            String label
    ) {}

    /**
     * 对话请求
     */
    public record ChatRequest(
            String message,
            String sessionId,
            String stage,
            Map<String, Object> context,
            UUID userId,      // 用户ID（用于创建工单）
            String userName,   // 用户名（用于创建工单）
            UUID tenantId      // 当前招聘 Tenant，作为 AI 授权归属
    ) {
        // 向后兼容：允许不传用户信息
        public ChatRequest(String message, String sessionId, String stage, Map<String, Object> context) {
            this(message, sessionId, stage, context, null, null, null);
        }
    }

    /**
     * 配置响应
     */
    public record ConfigResponse(
            String helpManualUrl,
            String cooperationQrCodeUrl,
            String cooperationPhone,
            String cooperationEmail
    ) {}

    // 会话存储（内存模式，生产环境用Redis）
    private final Map<String, AssistantSession> sessions = new ConcurrentHashMap<>();

    // 配置覆盖（测试/动态更新用，优先级高于@Value注入）
    private ConfigResponse configOverride;

    /**
     * 设置配置覆盖（便于测试和动态更新）
     */
    public void setConfig(ConfigResponse config) {
        this.configOverride = config;
    }

    /**
     * 获取配置（优先使用覆盖配置，否则使用@Value注入的配置）
     */
    public ConfigResponse getConfig() {
        if (configOverride != null) {
            return configOverride;
        }
        return new ConfigResponse(helpManualUrl, cooperationQrCodeUrl, cooperationPhone, cooperationEmail);
    }

    /**
     * 处理对话消息
     */
    public ChatResponse chat(ChatRequest request) {
        // 获取或创建会话
        AssistantSession session = getOrCreateSession(request.sessionId());
        AssistantStage currentStage = request.stage() != null
                ? AssistantStage.valueOf(request.stage())
                : session.stage();
        Map<String, Object> context = request.context() != null
                ? new ConcurrentHashMap<>(request.context())
                : new ConcurrentHashMap<>(session.context());
        context.put("_sessionId", session.sessionId());

        // 将用户信息存入上下文，供后续创建工单使用
        if (request.userId() != null) {
            context.put("_userId", request.userId());
        }
        if (request.userName() != null) {
            context.put("_userName", request.userName());
        }
        if (request.tenantId() != null) {
            context.put("_tenantId", request.tenantId());
        }

        String message = request.message();

        // 按阶段处理
        return switch (currentStage) {
            case GREETING -> handleGreeting(message, session, context);
            case HELP_MAIN, HELP_QA -> handleHelp(message, session, currentStage, context);
            case FEEDBACK_COLLECT -> handleFeedbackCollect(message, session, context);
            case FEEDBACK_CONFIRM -> handleFeedbackConfirm(message, session, context);
            case FEEDBACK_CONTACT -> handleFeedbackContact(message, session, context);
            case COOPERATION -> handleCooperation(message, session, context);
            case TICKET_CREATED -> handleTicketCreated(message, session, context);
        };
    }

    /**
     * 构建帮助问答的系统提示词
     */
    private String helpSystemPrompt() {
        ConfigResponse cfg = getConfig();
        return """
                你是"AI咨询助手"，一个专业的招聘平台客服助手。
                请用中文简洁回答用户的问题，语气友好专业。
                回答要结构化，使用列表或编号。
                相关帮助手册链接：%s
                平台主要功能：
                1. 智能招聘：AI生成JD、筛选候选人
                2. 简历管理：解析、筛选、匹配简历
                3. 面试题库：AI生成结构化面试题
                4. 账单管理：余额查询、充值
                5. 企业管理：企业认证、成员管理
                """.formatted(cfg.helpManualUrl());
    }

    /**
     * 构建反馈收集的系统提示词
     */
    private String feedbackSummaryPrompt() {
        return """
                你是"AI咨询助手"。用户正在反馈一个问题，请用简洁友好的中文总结用户描述的问题，以"我总结一下你描述的问题"开头。
                然后请用户确认是否准确。
                格式：
                感谢你的反馈！我总结一下你描述的问题：
                「总结内容」
                请确认以上描述是否准确？如果需要修改，可以直接告诉我。
                """;
    }

    /**
     * 构建反馈确认的系统提示词
     */
    private String feedbackContactPrompt() {
        return """
                你是"AI咨询助手"。用户已确认反馈内容，请用简洁友好的中文引导用户留下联系方式（手机号或邮箱）。
                说明这是为了方便处理进度反馈和问题沟通，承诺严格保护隐私。
                """;
    }

    /**
     * 构建合作需求的系统提示词
     */
    private String cooperationPrompt() {
        ConfigResponse cfg = getConfig();
        return """
                你是"AI咨询助手"。用户表达了合作意向，请用简洁友好的中文回应。
                先感谢用户，然后概述用户的合作需求，最后提供联系方式：
                联系电话：%s
                邮箱：%s
                说明商务团队会在1-2个工作日内联系。
                """.formatted(cfg.cooperationPhone(), cfg.cooperationEmail());
    }

    /**
     * 构建反馈提交成功的系统提示词
     */
    private String feedbackSuccessPrompt() {
        return """
                你是"AI咨询助手"。用户已提交反馈和联系方式，请用简洁友好的中文告知用户提交成功。
                内容包括：
                1. 确认反馈已提交
                2. 说明处理时间（1-3个工作日）
                3. 说明会通过联系方式反馈进度
                4. 感谢用户的反馈
                """;
    }

    // ==================== 对话阶段处理 ====================

    /**
     * 处理问候阶段
     */
    private ChatResponse handleGreeting(String message, AssistantSession session, Map<String, Object> context) {
        ConfigResponse cfg = getConfig();
        AssistantStage nextStage = AssistantStage.GREETING;
        String reply;
        List<ChatAction> actions = List.of(
                new ChatAction("help", "我需要帮助", "我需要帮助", "primary"),
                new ChatAction("feedback", "我想要反馈问题", "我想要反馈问题", "secondary"),
                new ChatAction("cooperation", "我有合作需求", "我有合作需求", "secondary")
        );
        List<ChatAttachment> attachments = List.of();

        // 初始问候：前端首次打开弹窗时发送 __GREETING__
        if ("__GREETING__".equals(message)) {
            reply = callLlmOrDefault(
                    """
                    你是"AI咨询助手"，一个专业的招聘平台客服助手。
                    请用中文友好地打招呼并自我介绍，然后引导用户选择以下三个选项：
                    1. 我需要帮助 - 获取使用指导
                    2. 我想要反馈问题 - 提交问题反馈
                    3. 我有合作需求 - 商务合作咨询
                    语气友好、简洁、专业。
                    """,
                    "用户首次进入助手，请打招呼并介绍自己。",
                    context
            );
            // 保持在GREETING阶段，让用户通过按钮选择
            sessions.put(session.sessionId(), new AssistantSession(session.sessionId(), nextStage, context));
            return new ChatResponse(reply, actions, attachments, nextStage, session.sessionId());
        }

        // 根据用户点击的按钮决定下一阶段
        if ("我需要帮助".equals(message)) {
            nextStage = AssistantStage.HELP_MAIN;
            reply = "好的！下面是一些常见的帮助主题，你也可以直接描述你遇到的问题，我会尽力帮你解答。\n\n📖 帮助手册：" + cfg.helpManualUrl();
            attachments = List.of(
                    new ChatAttachment("link", cfg.helpManualUrl(), cfg.helpManualUrl(), "点击查看完整帮助手册")
            );
            actions = List.of(
                    new ChatAction("jd_help", "如何生成JD？", "如何生成JD", "primary"),
                    new ChatAction("screening_help", "如何筛选简历？", "如何筛选简历", "secondary"),
                    new ChatAction("interview_help", "如何生成面试题？", "如何生成面试题", "secondary")
            );
        } else if ("我想要反馈问题".equals(message)) {
            nextStage = AssistantStage.FEEDBACK_COLLECT;
            reply = "好的，请描述你遇到的问题。尽量详细地说明，包括：\n1. 你在哪个页面遇到的问题\n2. 具体操作步骤\n3. 期望的结果是什么\n4. 实际发生了什么";
            actions = List.of();
        } else if ("我有合作需求".equals(message)) {
            nextStage = AssistantStage.COOPERATION;
            reply = "欢迎！请描述你的合作需求，包括：\n1. 合作类型（如：企业招聘合作、平台对接、渠道合作等）\n2. 合作意向和预期目标\n3. 联系方式（可选）\n\n我们会有商务团队尽快与您联系。";
            actions = List.of();
        } else {
            // 直接输入问题，进入帮助问答
            nextStage = AssistantStage.HELP_QA;
            reply = callLlmOrDefault(helpSystemPrompt(),
                    "用户问：" + message + "。请给出专业回答。",
                    context);
            attachments = List.of(
                    new ChatAttachment("link", cfg.helpManualUrl(), cfg.helpManualUrl(), "查看帮助手册")
            );
        }

        // 更新会话
        sessions.put(session.sessionId(), new AssistantSession(session.sessionId(), nextStage, context));

        return new ChatResponse(reply, actions, attachments, nextStage, session.sessionId());
    }

    /**
     * 处理帮助阶段（核心LLM问答）
     */
    private ChatResponse handleHelp(String message, AssistantSession session, AssistantStage currentStage, Map<String, Object> context) {
        ConfigResponse cfg = getConfig();
        AssistantStage nextStage = AssistantStage.HELP_QA;

        // 尝试调用LLM生成回复
        String reply = callLlmOrDefault(
                helpSystemPrompt(),
                "用户问题：" + message,
                context
        );

        List<ChatAction> actions = List.of(
                new ChatAction("back", "返回帮助菜单", "我需要帮助", "secondary"),
                new ChatAction("more", "继续提问", "__CONTINUE__", "primary")
        );
        List<ChatAttachment> attachments = List.of(
                new ChatAttachment("link", cfg.helpManualUrl(), cfg.helpManualUrl(), "查看帮助手册")
        );

        sessions.put(session.sessionId(), new AssistantSession(session.sessionId(), nextStage, context));
        return new ChatResponse(reply, actions, attachments, nextStage, session.sessionId());
    }

    /**
     * 处理反馈收集阶段
     */
    private ChatResponse handleFeedbackCollect(String message, AssistantSession session, Map<String, Object> context) {
        // 保存用户反馈内容
        context.put("feedbackContent", message);

        AssistantStage nextStage = AssistantStage.FEEDBACK_CONFIRM;

        // 尝试用LLM总结反馈
        String reply = callLlmOrDefault(
                feedbackSummaryPrompt(),
                "用户反馈内容：" + message,
                context
        );

        List<ChatAction> actions = List.of(
                new ChatAction("confirm", "确认无误", "确认提交", "primary"),
                new ChatAction("edit", "需要修改", "我想要反馈问题", "secondary")
        );

        sessions.put(session.sessionId(), new AssistantSession(session.sessionId(), nextStage, context));
        return new ChatResponse(reply, actions, List.of(), nextStage, session.sessionId());
    }

    /**
     * 处理反馈确认阶段
     */
    private ChatResponse handleFeedbackConfirm(String message, AssistantSession session, Map<String, Object> context) {
        AssistantStage nextStage = AssistantStage.FEEDBACK_CONTACT;
        String reply;
        List<ChatAction> actions = List.of();

        if ("确认提交".equals(message)) {
            reply = callLlmOrDefault(
                    feedbackContactPrompt(),
                    "用户已确认反馈，请引导留下联系方式。",
                    context
            );
            actions = List.of(
                    new ChatAction("skip", "暂不提供", "__SKIP_CONTACT__", "secondary")
            );
        } else {
            // 用户选择修改，回到收集阶段
            nextStage = AssistantStage.FEEDBACK_COLLECT;
            reply = "好的，请重新描述你遇到的问题。";
        }

        sessions.put(session.sessionId(), new AssistantSession(session.sessionId(), nextStage, context));
        return new ChatResponse(reply, actions, List.of(), nextStage, session.sessionId());
    }

    /**
     * 处理联系方式收集阶段
     * 用户完成反馈流程后，自动创建工单到平台工单系统。
     */
    private ChatResponse handleFeedbackContact(String message, AssistantSession session, Map<String, Object> context) {
        AssistantStage nextStage = AssistantStage.TICKET_CREATED;
        List<ChatAction> actions = List.of(
                new ChatAction("new_help", "还有其他问题", "我需要帮助", "primary"),
                new ChatAction("close", "结束对话", "__CLOSE__", "secondary")
        );

        // 保存联系方式
        if (!"__SKIP_CONTACT__".equals(message) && !message.isEmpty()) {
            context.put("contactInfo", message);
        }

        // 创建工单到平台工单系统
        createTicketFromFeedback(context);

        String reply;
        if ("__SKIP_CONTACT__".equals(message) || message.isEmpty()) {
            reply = "✅ 你的反馈已提交，我们会尽快处理。如需补充联系方式，可在后续对话中告知。感谢你的反馈！";
        } else {
            reply = callLlmOrDefault(
                    feedbackSuccessPrompt(),
                    "用户反馈内容：" + String.valueOf(context.get("feedbackContent")) + "，联系方式：" + message,
                    context
            );
        }

        sessions.put(session.sessionId(), new AssistantSession(session.sessionId(), nextStage, context));
        return new ChatResponse(reply, actions, List.of(), nextStage, session.sessionId());
    }

    /**
     * 从反馈上下文创建工单
     */
    private void createTicketFromFeedback(Map<String, Object> context) {
        try {
            UUID userId = (UUID) context.get("_userId");
            String userName = (String) context.get("_userName");
            UUID tenantId = (UUID) context.get("_tenantId");
            String feedbackContent = (String) context.get("feedbackContent");
            String contactInfo = (String) context.get("contactInfo");

            if (feedbackContent == null || feedbackContent.isBlank()) {
                return; // 没有反馈内容，不创建工单
            }

            // 构建工单标题（取前50个字符）
            String title = feedbackContent.length() > 50
                    ? feedbackContent.substring(0, 50) + "..."
                    : feedbackContent;

            // 构建工单正文（包含联系方式）
            String body = feedbackContent;
            if (contactInfo != null && !contactInfo.isBlank()) {
                body += "\n\n联系方式：" + contactInfo;
            }

            // 创建工单
            if (userId != null && userName != null && tenantId != null) {
                ticketService.createTicket(
                        userId,
                        tenantId,
                        userName,
                        title,
                        "FEEDBACK",
                        "NORMAL",
                        body
                );
            } else if (userId == null) {
                // 没有用户信息时，由管理员创建
                ticketService.createTicketByAdmin(
                        userName != null ? userName : "匿名用户",
                        title,
                        "FEEDBACK",
                        "NORMAL",
                        body
                );
            }
        } catch (Exception e) {
            // 工单创建失败不影响对话流程
            // 可以记录日志，但这里简化处理
        }
    }

    /**
     * 处理合作需求阶段
     * 用户提交合作需求后，自动创建工单到平台工单系统。
     */
    private ChatResponse handleCooperation(String message, AssistantSession session, Map<String, Object> context) {
        ConfigResponse cfg = getConfig();
        // 保存合作需求
        context.put("cooperationInfo", message);

        // 创建工单到平台工单系统
        createTicketFromCooperation(context);

        AssistantStage nextStage = AssistantStage.TICKET_CREATED;

        // 尝试用LLM生成回复
        String reply = callLlmOrDefault(
                cooperationPrompt(),
                "用户合作需求：" + message,
                context
        );

        List<ChatAttachment> attachments = List.of();
        if (cfg.cooperationQrCodeUrl() != null && !cfg.cooperationQrCodeUrl().isEmpty()) {
            attachments = List.of(
                    new ChatAttachment("qr_code", cfg.cooperationQrCodeUrl(), "", "扫码添加商务微信")
            );
        }

        List<ChatAction> actions = List.of(
                new ChatAction("submit", "提交合作需求", "提交合作需求", "primary"),
                new ChatAction("back", "返回", "我需要帮助", "secondary")
        );

        sessions.put(session.sessionId(), new AssistantSession(session.sessionId(), nextStage, context));
        return new ChatResponse(reply, actions, attachments, nextStage, session.sessionId());
    }

    /**
     * 从合作需求上下文创建工单
     */
    private void createTicketFromCooperation(Map<String, Object> context) {
        try {
            UUID userId = (UUID) context.get("_userId");
            String userName = (String) context.get("_userName");
            UUID tenantId = (UUID) context.get("_tenantId");
            String cooperationInfo = (String) context.get("cooperationInfo");

            if (cooperationInfo == null || cooperationInfo.isBlank()) {
                return; // 没有合作需求内容，不创建工单
            }

            // 构建工单标题
            String title = "合作咨询：" + (cooperationInfo.length() > 40
                    ? cooperationInfo.substring(0, 40) + "..."
                    : cooperationInfo);

            // 构建工单正文
            String body = cooperationInfo;

            // 创建工单
            if (userId != null && userName != null && tenantId != null) {
                ticketService.createTicket(
                        userId,
                        tenantId,
                        userName,
                        title,
                        "OTHER",
                        "NORMAL",
                        body
                );
            } else if (userId == null) {
                // 没有用户信息时，由管理员创建
                ticketService.createTicketByAdmin(
                        userName != null ? userName : "匿名用户",
                        title,
                        "OTHER",
                        "NORMAL",
                        body
                );
            }
        } catch (Exception e) {
            // 工单创建失败不影响对话流程
        }
    }

    /**
     * 处理工单创建后阶段
     */
    private ChatResponse handleTicketCreated(String message, AssistantSession session, Map<String, Object> context) {
        AssistantStage nextStage = AssistantStage.GREETING;
        String reply;
        List<ChatAction> actions = List.of(
                new ChatAction("help", "我需要帮助", "我需要帮助", "primary"),
                new ChatAction("feedback", "反馈新问题", "我想要反馈问题", "secondary"),
                new ChatAction("close", "结束对话", "__CLOSE__", "secondary")
        );

        if ("我需要帮助".equals(message) || message.startsWith("如何") || message.startsWith("怎么")) {
            nextStage = AssistantStage.HELP_QA;
            reply = callLlmOrDefault(helpSystemPrompt(), "用户问题：" + message,
                    context);
            actions = List.of();
        } else if ("我想要反馈问题".equals(message)) {
            nextStage = AssistantStage.FEEDBACK_COLLECT;
            reply = "好的，请描述你遇到的新问题。";
            actions = List.of();
        } else {
            reply = "好的，还有什么可以帮你的吗？\n你可以选择以下选项开始新的对话：";
        }

        sessions.put(session.sessionId(), new AssistantSession(session.sessionId(), nextStage, context));
        return new ChatResponse(reply, actions, List.of(), nextStage, session.sessionId());
    }

    // ==================== 辅助方法 ====================

    /**
     * Customer-facing AI replies are ordinary billable Agent executions: BOSS
     * evaluates membership, entitlement and credits before the model receives
     * any content, and Agent reports the resulting model usage afterwards.
     */
    private String callLlmOrDefault(String systemPrompt, String userMessage, Map<String, Object> context) {
        UUID actorId = requiredUuid(context, "_userId");
        UUID tenantId = requiredUuid(context, "_tenantId");
        var scope = tenantAccess.requireBusinessAccess(actorId, tenantId);
        var policy = flowCoordinator.evaluateAuthoritative(FlowCapability.CONVERSATION_CONTINUE, scope, actorId);
        UUID conversationId = UUID.nameUUIDFromBytes(("ai-assistant:" + context.getOrDefault("_sessionId", "new"))
                .getBytes(StandardCharsets.UTF_8));
        String idempotencyKey = "ai-assistant:" + UUID.randomUUID();
        ExecutionContext execution = flowCoordinator.createExecutionContext(policy, conversationId, idempotencyKey,
                "ai-assistant:" + conversationId,
                List.of(new ExecutionContext.InputVersion("assistant_message", conversationId.toString(), "current",
                        SecurityHashes.sha256(userMessage))), true);
        return aiPlatform.continueConversation(new ConversationAgentCommand(tenantId.toString(), actorId.toString(),
                conversationId.toString(), List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userMessage)), Map.of(), execution));
    }

    private static UUID requiredUuid(Map<String, Object> context, String key) {
        Object value = context.get(key);
        if (value instanceof UUID id) return id;
        if (value != null) {
            try { return UUID.fromString(String.valueOf(value)); }
            catch (IllegalArgumentException ignored) { }
        }
        throw new IllegalArgumentException("AI 咨询助手缺少 " + key + " 上下文");
    }

    /**
     * 获取或创建会话
     */
    private AssistantSession getOrCreateSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            AssistantSession existing = sessions.get(sessionId);
            if (existing != null) {
                return existing;
            }
        }
        // 创建新会话
        String newId = UUID.randomUUID().toString();
        AssistantSession newSession = new AssistantSession(
                newId,
                AssistantStage.GREETING,
                new ConcurrentHashMap<>()
        );
        sessions.put(newId, newSession);
        return newSession;
    }
}
