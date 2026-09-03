package com.intelligentrecruitment.aiplatform.application;

import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import java.util.function.Consumer;

public interface AiPlatformClient {

    AiTask startTask(StartAiTaskCommand command);

    default AiTask startTask(StartAiTaskCommand command, Consumer<String> onDelta) {
        return startTask(command);
    }

    AiTask getTask(String aiTaskId);

    AiTask cancelTask(String aiTaskId, String idempotencyKey);

    RouteDecision routeMessage(RouteAgentCommand command);

    String continueConversation(ConversationAgentCommand command);

    StructuredResult reviseJdInPlace(ConversationAgentCommand command);

    StructuredResult getStructuredResult(String aiTaskId);

    /**
     * 同步生成面试题包：基于职位快照 + 候选人简历解析结果，
     * 返回 匹配度总结 + 3 项核心胜任力 + 4~20 道面试题。
     * 不允许返回 null；任何 AI 侧异常（鉴权、超时、JSON 非法）都应降级到 Mock 输出保证业务可用。
     */
    InterviewQuestionContract.InterviewQuestionKit generateInterviewQuestions(
            InterviewQuestionContract.GenerateInterviewQuestionsInput input);
}
