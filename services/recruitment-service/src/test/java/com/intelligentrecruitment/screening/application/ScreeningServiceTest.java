package com.intelligentrecruitment.screening.application;

import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.PolicyDecision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningServiceTest {

    @Test
    void keepsMultiWordSkillNamesIntact() {
        assertThat(ScreeningService.tokens("Java, Spring Boot，MySQL / Redis; Kafka"))
                .containsExactly("Java", "Spring Boot", "MySQL", "Redis", "Kafka");
    }

    @Test
    void givesEachCandidateItsOwnAuthorizedIdempotencyAndPiiContext() {
        UUID tenantId = UUID.randomUUID(); UUID actorId = UUID.randomUUID(); UUID itemId = UUID.randomUUID();
        ExecutionContext run = new ExecutionContext(UUID.randomUUID(), null, "request", "trace", tenantId, actorId,
                UUID.randomUUID(), "screening-run", FlowCapability.CANDIDATE_SCREENING, "screening-run",
                List.of(new ExecutionContext.InputVersion("job_version", "job-v1", "frozen", "job-hash"),
                        new ExecutionContext.InputVersion("screening_plan_version", "plan-v2", "frozen", "plan-hash")),
                new PolicyDecision(UUID.randomUUID(), FlowCapability.CANDIDATE_SCREENING,
                PolicyDecision.Decision.ALLOW, List.of(PolicyDecision.ReasonCode.AUTHORIZED), tenantId, actorId,
                "v1", Instant.now()), new ExecutionContext.DataHandling(false, "ephemeral", false), Instant.now(),
                new ExecutionContext.AgentRoute("complex_recruitment_agent", "match", "route-v4", "flag"));
        UUID parseVersionId = UUID.randomUUID();

        ExecutionContext.InputVersion fileVersion = new ExecutionContext.InputVersion("resume_file", "file-1", "1", "sha256");
        ExecutionContext context = ScreeningService.itemExecutionContext(run, itemId, parseVersionId, fileVersion);

        assertThat(context.idempotencyKey()).isEqualTo("screening-item:" + itemId);
        assertThat(context.businessTaskId()).isEqualTo(itemId);
        assertThat(context.executionId()).isEqualTo(itemId);
        assertThat(context.dataHandling().containsPii()).isTrue();
        assertThat(context.agentRoute()).isEqualTo(run.agentRoute());
        assertThat(context.inputVersions()).contains(fileVersion)
                .extracting(ExecutionContext.InputVersion::kind)
                .containsExactly("job_version", "screening_plan_version", "resume_parse_version", "resume_file");
    }
}
