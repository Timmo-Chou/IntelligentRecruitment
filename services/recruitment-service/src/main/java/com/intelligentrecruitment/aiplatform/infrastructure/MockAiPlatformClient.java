package com.intelligentrecruitment.aiplatform.infrastructure;

import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.shared.error.ApiException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai-platform.mode", havingValue = "mock", matchIfMissing = true)
public class MockAiPlatformClient implements AiPlatformClient {

    private final Map<String, AiTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();

    @Override
    public AiTask startTask(StartAiTaskCommand command) {
        String existingTaskId = idempotencyIndex.get(command.idempotencyKey());
        if (existingTaskId != null) {
            return tasks.get(existingTaskId);
        }

        String aiTaskId = "mock_ait_" + UUID.randomUUID();
        AiTask task = new AiTask(
                aiTaskId,
                command.businessTaskId(),
                command.capability(),
                AiTaskStatus.QUEUED,
                0,
                1,
                0,
                Instant.now()
        );
        tasks.put(aiTaskId, task);
        idempotencyIndex.put(command.idempotencyKey(), aiTaskId);
        return task;
    }

    @Override
    public AiTask getTask(String aiTaskId) {
        AiTask task = tasks.get(aiTaskId);
        if (task == null) {
            throw new ApiException("AI_TASK_NOT_FOUND", "AI 任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    @Override
    public AiTask cancelTask(String aiTaskId, String idempotencyKey) {
        AiTask current = getTask(aiTaskId);
        if (current.status() == AiTaskStatus.COMPLETED || current.status() == AiTaskStatus.FAILED) {
            return current;
        }
        AiTask cancelled = new AiTask(
                current.aiTaskId(),
                current.businessTaskId(),
                current.capability(),
                AiTaskStatus.CANCELLED,
                current.completed(),
                current.total(),
                current.percent(),
                current.acceptedAt()
        );
        tasks.put(aiTaskId, cancelled);
        return cancelled;
    }
}

