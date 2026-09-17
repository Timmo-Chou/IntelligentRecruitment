package com.intelligentrecruitment.aiplatform.application;

import java.util.List;
import java.util.Map;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;

/** Bounded, task-scoped conversation context. The business service remains the source of truth. */
public record ConversationAgentCommand(
        String tenantId,
        String actorId,
        String businessTaskId,
        List<Map<String, String>> messages,
        Map<String, Object> jdDraft,
        ExecutionContext executionContext
) {
}
