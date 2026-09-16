package com.intelligentrecruitment.aiplatform.infrastructure;

import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.aiplatform.application.*;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 委派客户端：按能力路由到不同实现。
 * P2 迁移（走 HttpAiPlatformClient → AIAgentPlatform）：
 *   JD_GENERATION、RESUME_PARSING、CANDIDATE_SCREENING、
 *   INTERVIEW_KIT_GENERATION、REQUIREMENT_CHAT（路由）
 * 仍走 DeepSeek（待 P3）：continueConversation、reviseJdInPlace
 *
 * 查询/取消/结果：按任务 ID 前缀路由。
 * - deepseek_ait_ 前缀 → DeepSeek
 * - UUID 格式 → Http（AIAgentPlatform）
 *
 * 已迁移的招聘能力始终走 AIAgentPlatform；旧任务按任务 ID 前缀保持可读取。
 */
@Component
@Primary
public class DelegatingAiPlatformClient implements AiPlatformClient {

    private static final String DEEPSEEK_PREFIX = "deepseek_ait_";

    private final HttpAiPlatformClient httpClient;
    private final DeepSeekAiPlatformClient deepSeekClient;
    private final boolean useHttp;

    public DelegatingAiPlatformClient(HttpAiPlatformClient httpClient,
                                      DeepSeekAiPlatformClient deepSeekClient,
                                      @org.springframework.beans.factory.annotation.Value("${app.ai-platform.use-http-client:true}") boolean useHttp) {
        this.httpClient = httpClient;
        this.deepSeekClient = deepSeekClient;
        this.useHttp = useHttp;
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command) {
        if (isHttpCapability(command.capability())) {
            return httpClient.startTask(command);
        }
        return deepSeekClient.startTask(command);
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command, java.util.function.Consumer<String> onDelta) {
        // HTTP 路径不支持流式 delta；DeepSeek 路径保留流式
        if (isHttpCapability(command.capability())) {
            return httpClient.startTask(command);
        }
        return deepSeekClient.startTask(command, onDelta);
    }

    @Override
    public AiTask getTask(String aiTaskId, String actorId) {
        return route(aiTaskId).getTask(aiTaskId, actorId);
    }

    @Override
    public AiTask cancelTask(String aiTaskId, String idempotencyKey, String actorId) {
        return route(aiTaskId).cancelTask(aiTaskId, idempotencyKey, actorId);
    }

    @Override
    public StructuredResult getStructuredResult(String aiTaskId, String actorId) {
        return route(aiTaskId).getStructuredResult(aiTaskId, actorId);
    }

    @Override
    public RouteDecision routeMessage(RouteAgentCommand command) {
        return httpClient.routeMessage(command);
    }

    @Override
    public String continueConversation(ConversationAgentCommand command) {
        return httpClient.continueConversation(command);
    }

    @Override
    public StructuredResult reviseJdInPlace(ConversationAgentCommand command) {
        return httpClient.reviseJdInPlace(command);
    }

    // ===== 内部路由 =====

    private boolean isHttpCapability(AiCapability capability) {
        return capability == AiCapability.JD_GENERATION
                || capability == AiCapability.RESUME_PARSING
                || capability == AiCapability.CANDIDATE_SCREENING
                || capability == AiCapability.INTERVIEW_KIT_GENERATION
                || capability == AiCapability.CONVERSATION_CONTINUE
                || capability == AiCapability.JD_IN_PLACE_REVISION;
    }

    private AiPlatformClient route(String aiTaskId) {
        if (aiTaskId != null && !aiTaskId.startsWith(DEEPSEEK_PREFIX)) {
            return httpClient;
        }
        return deepSeekClient;
    }
}
