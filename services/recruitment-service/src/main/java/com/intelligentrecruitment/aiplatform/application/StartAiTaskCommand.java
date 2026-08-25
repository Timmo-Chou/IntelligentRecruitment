package com.intelligentrecruitment.aiplatform.application;

import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import java.util.Map;

public record StartAiTaskCommand(
        String workspaceId,
        String companyId,
        String actorId,
        String businessTaskId,
        String idempotencyKey,
        AiCapability capability,
        Map<String, Object> input
) {
}
