package com.intelligentrecruitment.aiplatform.application;

import java.util.List;
import java.util.Map;

/** Bounded, task-scoped conversation context. The business service remains the source of truth. */
public record ConversationAgentCommand(
        String workspaceId,
        String companyId,
        String actorId,
        String businessTaskId,
        List<Map<String, String>> messages,
        Map<String, Object> jdDraft
) {
}
