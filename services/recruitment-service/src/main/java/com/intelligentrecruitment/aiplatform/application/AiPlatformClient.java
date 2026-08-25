package com.intelligentrecruitment.aiplatform.application;

import com.intelligentrecruitment.aiplatform.domain.AiTask;

public interface AiPlatformClient {

    AiTask startTask(StartAiTaskCommand command);

    AiTask getTask(String aiTaskId);

    AiTask cancelTask(String aiTaskId, String idempotencyKey);
}

