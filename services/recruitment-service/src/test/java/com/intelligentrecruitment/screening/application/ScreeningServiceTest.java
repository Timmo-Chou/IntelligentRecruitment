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
                List.of(), new PolicyDecision(UUID.randomUUID(), FlowCapability.CANDIDATE_SCREENING,
                PolicyDecision.Decision.ALLOW, List.of(PolicyDecision.ReasonCode.AUTHORIZED), tenantId, actorId,
                "v1", Instant.now()), new ExecutionContext.DataHandling(false, "ephemeral", false), Instant.now());
        UUID parseVersionId = UUID.randomUUID();

        ExecutionContext context = ScreeningService.itemExecutionContext(run, itemId, parseVersionId);

        assertThat(context.idempotencyKey()).isEqualTo("screening-item:" + itemId);
        assertThat(context.businessTaskId()).isEqualTo(itemId);
        assertThat(context.dataHandling().containsPii()).isTrue();
    }
}
