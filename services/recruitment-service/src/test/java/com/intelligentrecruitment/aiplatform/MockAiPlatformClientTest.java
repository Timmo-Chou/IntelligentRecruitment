package com.intelligentrecruitment.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.application.RouteAgentCommand;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.infrastructure.MockAiPlatformClient;
import java.util.Map;
import java.util.List;
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
                Map.of("title", "工艺工程师"),
                null
        );

        AiTask first = client.startTask(command);
        AiTask second = client.startTask(command);

        assertThat(second.aiTaskId()).isEqualTo(first.aiTaskId());
        assertThat(client.getStructuredResult(first.aiTaskId()).data()).containsEntry("title", "工艺工程师");
    }

    @Test
    void routesCandidateScreeningWithoutAuthorizingIt() {
        var decision = client.routeMessage(new RouteAgentCommand("req-1", "trc-1", "workspace-1", "company-1",
                "user-1", "task-1", "帮我筛这批 Java 简历",
                List.of(FlowCapability.JD_GENERATION, FlowCapability.CANDIDATE_SCREENING)));

        assertThat(decision.capability()).isEqualTo(FlowCapability.CANDIDATE_SCREENING);
        assertThat(decision.secondaryIntent()).isEqualTo("create_screening_run");
        assertThat(decision.operation()).isEqualTo(com.intelligentrecruitment.agentflow.domain.RouteDecision.Operation.CREATE);
        assertThat(decision.requiresPolicyCheck()).isTrue();
        assertThat(decision.missingInputs()).containsExactly("job_version", "candidate_scope", "screening_plan");
    }

    @Test
    void classifiesExternalCandidateSearchWithoutInvokingAProvider() {
        var decision = client.routeMessage(new RouteAgentCommand("req-2", "trc-2", "workspace-1", "company-1",
                "user-1", "task-1", "去聘才猫找符合 Java 岗位的人",
                List.of(FlowCapability.CANDIDATE_SOURCING)));

        assertThat(decision.kind()).isEqualTo(com.intelligentrecruitment.agentflow.domain.RouteDecision.Kind.ROUTE);
        assertThat(decision.capability()).isEqualTo(FlowCapability.CANDIDATE_SOURCING);
        assertThat(decision.secondaryIntent()).isEqualTo("search_external_candidates");
        assertThat(decision.operation()).isEqualTo(com.intelligentrecruitment.agentflow.domain.RouteDecision.Operation.CREATE);
        assertThat(decision.requiresPolicyCheck()).isTrue();
    }

    @Test
    void returnsStructuredCandidateScreeningResult() {
        AiTask task = client.startTask(new StartAiTaskCommand(
                "workspace-1", "company-1", "user-1", "run-item-1", "screening-item-key",
                AiCapability.CANDIDATE_SCREENING,
                Map.of("job", Map.of("skills", "Java,Spring Boot"),
                        "candidate", Map.of("skills", "Java,Spring Boot", "resume_text", "负责 Java 服务端开发")), null));

        assertThat(client.getStructuredResult(task.aiTaskId()).capability()).isEqualTo(FlowCapability.CANDIDATE_SCREENING);
        assertThat(client.getStructuredResult(task.aiTaskId()).data())
                .containsKeys("score", "level", "matched_points", "unmatched_points", "evidence");
    }
}
