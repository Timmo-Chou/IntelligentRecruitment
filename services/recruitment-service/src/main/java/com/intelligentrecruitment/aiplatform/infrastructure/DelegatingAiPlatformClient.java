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
 * feature flag：app.ai-platform.use-http-client=false 时全部走 DeepSeek（可回退）。
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
        if (useHttp && isHttpCapability(command.capability())) {
            return httpClient.startTask(command);
        }
        return deepSeekClient.startTask(command);
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command, java.util.function.Consumer<String> onDelta) {
        // HTTP 路径不支持流式 delta；DeepSeek 路径保留流式
        if (useHttp && isHttpCapability(command.capability())) {
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
        if (useHttp) return httpClient.routeMessage(command);
        return deepSeekClient.routeMessage(command);
    }

    @Override
    public String continueConversation(ConversationAgentCommand command) {
        // 尚未携带 PolicyDecision 的旧对话协议不允许穿过新的 AI Platform 入口。
        // 在其迁移为 CapabilityExecutionRequest 前，保留既有本地适配器路径。
        return deepSeekClient.continueConversation(command);
    }

    @Override
    public StructuredResult reviseJdInPlace(ConversationAgentCommand command) {
        return deepSeekClient.reviseJdInPlace(command);
    }

    @Override
    public InterviewQuestionContract.InterviewQuestionKit generateInterviewQuestions(
            InterviewQuestionContract.GenerateInterviewQuestionsInput input) {
        return deepSeekClient.generateInterviewQuestions(input);
    }

    // ===== 内部路由 =====

    private boolean isHttpCapability(AiCapability capability) {
        return capability == AiCapability.JD_GENERATION
                || capability == AiCapability.RESUME_PARSING
                || capability == AiCapability.CANDIDATE_SCREENING;
    }

    private AiPlatformClient route(String aiTaskId) {
        if (useHttp && aiTaskId != null && !aiTaskId.startsWith(DEEPSEEK_PREFIX)) {
            return httpClient;
        }
        return deepSeekClient;
    }
}
