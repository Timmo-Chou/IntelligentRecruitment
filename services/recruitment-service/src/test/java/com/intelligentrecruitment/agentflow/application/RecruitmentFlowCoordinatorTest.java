package com.intelligentrecruitment.agentflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.PolicyDecision;
import com.intelligentrecruitment.tenancy.application.TenantAccessService.TenantScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecruitmentFlowCoordinatorTest {

    private final RecruitmentFlowCoordinator coordinator = new RecruitmentFlowCoordinator();
    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final TenantScope scope = new TenantScope(tenantId, "ENTERPRISE", "招聘组", "RECRUITER");

    @Test
    void authorizesCapabilityWithoutLocalPricingOrBalanceDecision() {
        PolicyDecision decision = coordinator.evaluateAuthoritative(FlowCapability.CANDIDATE_SCREENING, scope, actorId);

        assertThat(decision.decision()).isEqualTo(PolicyDecision.Decision.ALLOW);
        assertThat(decision.reasonCodes()).containsExactly(PolicyDecision.ReasonCode.AUTHORIZED);
    }

    @Test
    void freezesAnAllowedExecutionWithScopeAndInputVersions() {
        PolicyDecision decision = coordinator.evaluateAuthoritative(FlowCapability.CANDIDATE_SCREENING, scope, actorId);
        UUID businessTaskId = UUID.randomUUID();

        ExecutionContext context = coordinator.createExecutionContext(decision, businessTaskId, "key-12345678",
                "screening-run:" + businessTaskId,
                List.of(new ExecutionContext.InputVersion("job_version", "jv_01", "frozen", "hash")), false);

        assertThat(context.policyDecision()).isSameAs(decision);
        assertThat(context.tenantId()).isEqualTo(tenantId);
        assertThat(context.actorId()).isEqualTo(actorId);
        assertThat(context.inputVersions()).singleElement().extracting(ExecutionContext.InputVersion::kind)
                .isEqualTo("job_version");
        assertThat(context.dataHandling().logContent()).isFalse();
    }
}
