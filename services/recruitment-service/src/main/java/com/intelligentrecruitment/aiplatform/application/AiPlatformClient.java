package com.intelligentrecruitment.aiplatform.application;

import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;

public interface AiPlatformClient {

    AiTask startTask(StartAiTaskCommand command);

    AiTask getTask(String aiTaskId);

    AiTask cancelTask(String aiTaskId, String idempotencyKey);

    RouteDecision routeMessage(RouteAgentCommand command);

    String continueConversation(ConversationAgentCommand command);

    StructuredResult reviseJdInPlace(ConversationAgentCommand command);

    StructuredResult getStructuredResult(String aiTaskId);
}
