package com.intelligentrecruitment.aiplatform.infrastructure;

import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.aiplatform.application.*;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Recruitment AI 的唯一调用边界。
 *
 * BOSS 负责授权、预占与结算，AIAgentPlatform 负责模型执行与用量回传；
 * IntelligentRecruitment 不保留任何直连模型的能力路由或本地生成降级路径。
 */
@Component
@Primary
public class DelegatingAiPlatformClient implements AiPlatformClient {
    private final HttpAiPlatformClient httpClient;

    public DelegatingAiPlatformClient(HttpAiPlatformClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command) {
        return httpClient.startTask(command);
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command, java.util.function.Consumer<String> onDelta) {
        // AIAgentPlatform 的异步任务协议不传递流式 delta。
        return httpClient.startTask(command);
    }

    @Override
    public AiTask getTask(String aiTaskId, String actorId) {
        return httpClient.getTask(aiTaskId, actorId);
    }

    @Override
    public AiTask cancelTask(String aiTaskId, String idempotencyKey, String actorId) {
        return httpClient.cancelTask(aiTaskId, idempotencyKey, actorId);
    }

    @Override
    public StructuredResult getStructuredResult(String aiTaskId, String actorId) {
        return httpClient.getStructuredResult(aiTaskId, actorId);
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

}
