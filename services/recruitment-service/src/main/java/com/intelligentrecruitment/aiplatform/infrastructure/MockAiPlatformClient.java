package com.intelligentrecruitment.aiplatform.infrastructure;

import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.ConversationAgentCommand;
import com.intelligentrecruitment.aiplatform.application.RouteAgentCommand;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.shared.error.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai-platform.mode", havingValue = "mock")
public class MockAiPlatformClient implements AiPlatformClient {

    private final Map<String, AiTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Map<String, StructuredResult> results = new ConcurrentHashMap<>();

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
        results.put(aiTaskId, resultFor(aiTaskId, command));
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

    @Override
    public RouteDecision routeMessage(RouteAgentCommand command) {
        String message = command.message() == null ? "" : command.message().toLowerCase();
        FlowCapability capability = message.contains("面试") ? FlowCapability.INTERVIEW_KIT_GENERATION
                : message.contains("筛") || message.contains("匹配") ? FlowCapability.CANDIDATE_SCREENING
                : message.contains("简历") || message.contains("解析") ? FlowCapability.RESUME_PARSING
                : message.contains("jd") || message.contains("职位") || message.contains("岗位")
                ? FlowCapability.JD_GENERATION : null;
        if (capability == null || !command.allowedCapabilities().contains(capability)) {
            return new RouteDecision(UUID.randomUUID(), RouteDecision.Kind.CLARIFY, null, 0.35, true,
                    List.of("user_clarification"), "请说明你希望生成 JD、解析简历、筛选候选人，还是设计面试题。",
                    RouteDecision.SuggestedNextAction.COLLECT_REQUIREMENT, Instant.now());
        }
        List<String> missing = capability == FlowCapability.CANDIDATE_SCREENING
                ? List.of("job_version", "candidate_scope", "screening_plan") : List.of();
        RouteDecision.SuggestedNextAction action = capability == FlowCapability.CANDIDATE_SCREENING
                ? RouteDecision.SuggestedNextAction.SELECT_JOB_VERSION : RouteDecision.SuggestedNextAction.COLLECT_REQUIREMENT;
        return new RouteDecision(UUID.randomUUID(), RouteDecision.Kind.ROUTE, capability, 0.92, true,
                missing, null, action, Instant.now());
    }

    @Override
    public String continueConversation(ConversationAgentCommand command) {
        Map<String, String> latest = command.messages().isEmpty() ? Map.of() : command.messages().getLast();
        return "已收到你的补充：" + latest.getOrDefault("content", "").trim()
                + "。我会基于当前任务上下文继续协助；确认后可生成新的 JD 修订版本。";
    }

    @Override
    public StructuredResult reviseJdInPlace(ConversationAgentCommand command) {
        return new StructuredResult(UUID.randomUUID(), "mock_revision_" + UUID.randomUUID(), FlowCapability.JD_GENERATION,
                StructuredResult.Status.DRAFT_READY, "jd-v1", command.jdDraft(), List.of(), List.of(),
                new StructuredResult.Provenance("mock-jd", "v1", "mock-revision-v1", "mock"),
                new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    @Override
    public StructuredResult getStructuredResult(String aiTaskId) {
        StructuredResult result = results.get(aiTaskId);
        if (result == null) throw new ApiException("AI_TASK_NOT_FOUND", "AI 任务不存在", HttpStatus.NOT_FOUND);
        return result;
    }

    private StructuredResult resultFor(String aiTaskId, StartAiTaskCommand command) {
        if (command.capability() == com.intelligentrecruitment.aiplatform.domain.AiCapability.JD_GENERATION) {
            Map<String, Object> input = command.input();
            String title = text(input, "title", "待确认职位");
            String requirement = text(input, "requirement", "请补充岗位需求");
            String skills = text(input, "skills", "岗位专业能力、沟通协作、问题解决");
            Map<String, Object> data = Map.ofEntries(
                    Map.entry("title", title),
                    Map.entry("company_name", text(input, "companyName", "企业名称待确认")),
                    Map.entry("location", text(input, "location", "工作地点待确认")),
                    Map.entry("experience_level", text(input, "experienceLevel", "经验待确认")),
                    Map.entry("education", text(input, "education", "学历待确认")),
                    Map.entry("job_type", text(input, "jobType", "全职")),
                    Map.entry("responsibilities", "1. 围绕“%s”推进岗位核心工作；\n2. 与团队协作交付可验证的业务结果。".formatted(requirement)),
                    Map.entry("requirements", "具备与岗位相关的专业能力、结构化沟通与问题解决能力。"),
                    Map.entry("skills", skills),
                    Map.entry("talent_profile", "优先寻找具备“%s”能力组合且有可验证成果的人选。".formatted(skills)),
                    Map.entry("warnings", List.of("薪资范围尚未提供"))
            );
            return new StructuredResult(command.executionContext() == null ? null : command.executionContext().executionId(),
                    aiTaskId, FlowCapability.JD_GENERATION, StructuredResult.Status.DRAFT_READY, "jd-v1", data,
                    List.of(), List.of(), new StructuredResult.Provenance("mock-jd", "v1", "mock-jd-v1", "mock"),
                    new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
        }
        return new StructuredResult(command.executionContext() == null ? null : command.executionContext().executionId(),
                aiTaskId, toFlowCapability(command.capability()), StructuredResult.Status.COMPLETED, "v1", Map.of(),
                List.of(), List.of(), new StructuredResult.Provenance("mock-" + command.capability().name().toLowerCase(),
                "v1", "mock-v1", "mock"), new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    private static String text(Map<String, Object> input, String field, String fallback) {
        Object value = input.get(field);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static FlowCapability toFlowCapability(com.intelligentrecruitment.aiplatform.domain.AiCapability capability) {
        return FlowCapability.fromValue(capability.name());
    }
}
