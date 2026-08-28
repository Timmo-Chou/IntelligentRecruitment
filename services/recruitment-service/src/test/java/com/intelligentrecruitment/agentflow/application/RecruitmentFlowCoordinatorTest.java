package com.intelligentrecruitment.agentflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.PolicyDecision;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService.WorkspaceScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecruitmentFlowCoordinatorTest {

    private final RecruitmentFlowCoordinator coordinator = new RecruitmentFlowCoordinator();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final WorkspaceScope scope = new WorkspaceScope(workspaceId, companyId, "COMPANY", "招聘组", "RECRUITER");

    @Test
    void requiresConfirmationBeforeAQuotedCapabilityCanExecute() {
        PolicyDecision decision = coordinator.evaluate(FlowCapability.CANDIDATE_SCREENING, scope, actorId,
                1_000, 160, UUID.randomUUID(), false);

        assertThat(decision.decision()).isEqualTo(PolicyDecision.Decision.REQUIRE_USER_CONFIRMATION);
        assertThat(decision.reasonCodes()).containsExactly(PolicyDecision.ReasonCode.AUTHORIZED,
                PolicyDecision.ReasonCode.USER_CONFIRMATION_REQUIRED);
        assertThatThrownBy(() -> coordinator.createExecutionContext(decision, UUID.randomUUID(), "key-12345678",
                "screening-run:test", List.of(), false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("POLICY_DENIED");
    }

    @Test
    void deniesExecutionWhenTheWorkspaceBalanceIsInsufficient() {
        PolicyDecision decision = coordinator.evaluate(FlowCapability.JD_GENERATION, scope, actorId,
                79, 80, null, true);

        assertThat(decision.decision()).isEqualTo(PolicyDecision.Decision.DENY);
        assertThat(decision.reasonCodes()).containsExactly(PolicyDecision.ReasonCode.INSUFFICIENT_BALANCE);
        assertThatThrownBy(() -> coordinator.createExecutionContext(decision, UUID.randomUUID(), "key-12345678",
                "jd-run:test", List.of(), false))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @Test
    void freezesAnAllowedExecutionWithScopeAndInputVersions() {
        PolicyDecision decision = coordinator.evaluate(FlowCapability.CANDIDATE_SCREENING, scope, actorId,
                1_000, 160, UUID.randomUUID(), true);
        UUID businessTaskId = UUID.randomUUID();

        ExecutionContext context = coordinator.createExecutionContext(decision, businessTaskId, "key-12345678",
                "screening-run:" + businessTaskId,
                List.of(new ExecutionContext.InputVersion("job_version", "jv_01", "frozen", "hash")), false);

        assertThat(context.policyDecision()).isSameAs(decision);
        assertThat(context.workspaceId()).isEqualTo(workspaceId);
        assertThat(context.companyId()).isEqualTo(companyId);
        assertThat(context.actorId()).isEqualTo(actorId);
        assertThat(context.inputVersions()).singleElement().extracting(ExecutionContext.InputVersion::kind)
                .isEqualTo("job_version");
        assertThat(context.dataHandling().logContent()).isFalse();
    }
}
