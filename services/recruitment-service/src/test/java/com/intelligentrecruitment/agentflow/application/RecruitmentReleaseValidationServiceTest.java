package com.intelligentrecruitment.agentflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.domain.*;
import com.intelligentrecruitment.candidates.application.PiiCipher;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecruitmentReleaseValidationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper json = mock(ObjectMapper.class);
    private final AiPlatformClient ai = mock(AiPlatformClient.class);
    private final RecruitmentReleaseValidationService service = new RecruitmentReleaseValidationService(jdbc, json,
            mock(RecruitmentFlowCoordinator.class), mock(RecruitmentExecutionMaintenance.class), ai,
            mock(PiiCipher.class), new RecruitmentRouteResolver("true", "test-v1"), "local-build");
    private final UUID validation = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final String taskId = UUID.randomUUID().toString();

    private void prepare(Map<String, Object> decision) throws Exception {
        when(jdbc.queryForList(startsWith("SELECT id,tenant_id,actor_id"))).thenReturn(List.of(Map.of(
                "id", validation, "tenant_id", tenant, "actor_id", actor, "input_json", "{}",
                "context_json", "context", "ai_task_id", taskId)));
        when(json.readValue("context", ExecutionContext.class)).thenReturn(new ExecutionContext(UUID.randomUUID(), null,
                "synthetic-request", "synthetic-trace", tenant, actor, validation, "synthetic-key",
                com.intelligentrecruitment.agentflow.domain.FlowCapability.JD_GENERATION,
                "synthetic-validation", List.of(), null, null, Instant.now()));
        when(ai.getTask(taskId, actor.toString())).thenReturn(new AiTask(taskId, validation.toString(),
                AiCapability.JD_GENERATION, AiTaskStatus.FAILED, 0, 1, 100, 0, Instant.now(),
                "RD_CONFIRMED_NO_VALID_RESULT", "Synthetic provider failure", 6));
        when(jdbc.queryForList(startsWith("SELECT e.final_decision"), eq(tenant), eq(validation.toString())))
                .thenReturn(List.of(decision));
    }

    @Test void previouslyReleasedValidationClosesWithoutReplacingItsSavedReleaseReason() throws Exception {
        prepare(Map.of("final_decision", "RELEASE", "agent_task_id", taskId, "effect_type", "RELEASE"));
        doThrow(new IllegalStateException("A different release reason must conflict"))
                .when(ai).confirmNoBillableResult(anyString(), anyString());

        service.processOne();

        verify(jdbc).update(startsWith("UPDATE recruitment_release_validation_runs SET status='FAILED'"),
                eq("RD_CONFIRMED_NO_VALID_RESULT"), eq(validation));
        verify(ai, never()).confirmNoBillableResult(anyString(), anyString());
        verify(jdbc, never()).update(startsWith("UPDATE recruitment_release_validation_runs SET error_code="), any(Object[].class));
    }

    @Test void failureWithoutAFinalDecisionStillCreatesItsNormalRelease() throws Exception {
        var decision = new LinkedHashMap<String, Object>();
        decision.put("final_decision", null);
        decision.put("agent_task_id", taskId);
        decision.put("effect_type", null);
        prepare(decision);

        service.processOne();

        verify(ai).confirmNoBillableResult(taskId, "RELEASE_VALIDATION_FAILED");
        verify(jdbc).update(startsWith("UPDATE recruitment_release_validation_runs SET status='FAILED'"),
                eq("RD_CONFIRMED_NO_VALID_RESULT"), eq(validation));
    }

    @Test void conflictingCaptureIsNotOverwrittenByAFailureProjection() throws Exception {
        prepare(Map.of("final_decision", "CAPTURE", "agent_task_id", taskId, "effect_type", "USAGE"));

        service.processOne();

        verify(ai, never()).confirmNoBillableResult(anyString(), anyString());
        verify(jdbc, never()).update(startsWith("UPDATE recruitment_release_validation_runs SET status='FAILED'"), any(Object[].class));
        verify(jdbc).update(startsWith("UPDATE recruitment_release_validation_runs SET error_code='VALIDATION_REQUIRES_RECONCILIATION'"), eq(validation));
    }
}
