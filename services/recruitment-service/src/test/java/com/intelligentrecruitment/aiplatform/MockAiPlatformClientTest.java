package com.intelligentrecruitment.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.infrastructure.MockAiPlatformClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MockAiPlatformClientTest {

    private final MockAiPlatformClient client = new MockAiPlatformClient();

    @Test
    void returnsTheSameTaskForTheSameIdempotencyKey() {
        StartAiTaskCommand command = new StartAiTaskCommand(
                "workspace-1",
                "company-1",
                "user-1",
                "business-task-1",
                "stable-idempotency-key",
                AiCapability.JD_GENERATION,
                Map.of("title", "工艺工程师")
        );

        AiTask first = client.startTask(command);
        AiTask second = client.startTask(command);

        assertThat(second.aiTaskId()).isEqualTo(first.aiTaskId());
    }
}
