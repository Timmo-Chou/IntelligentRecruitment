package com.intelligentrecruitment.aiplatform.domain;

import java.time.Instant;

public record AiTask(
        String aiTaskId,
        String businessTaskId,
        AiCapability capability,
        AiTaskStatus status,
        int completed,
        int total,
        int percent,
        Instant acceptedAt
) {
}

