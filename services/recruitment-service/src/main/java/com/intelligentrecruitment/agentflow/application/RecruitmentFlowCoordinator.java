package com.intelligentrecruitment.agentflow.application;

import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.PolicyDecision;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.tenancy.application.TenantAccessService.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The deterministic business-side gate for AI capability execution. It does not
 * choose a Skill or invoke a model; it only freezes the authority and context
 * that the AI Platform may use after a user-confirmed business command.
 */
@Service
public class RecruitmentFlowCoordinator {

    public static final String POLICY_VERSION = "recruitment-flow-v1";
    /**
     * Recruitment only freezes business context.  Capability entitlement and
     * credit reservation are evaluated atomically by BOSS when AIAgentPlatform
     * accepts the execution request; this service must not keep a second local
     * monetary or quota decision.
     */
    public PolicyDecision evaluateAuthoritative(FlowCapability capability, TenantScope scope, UUID actorId) {
        return decision(capability, scope, actorId, PolicyDecision.Decision.ALLOW,
                List.of(PolicyDecision.ReasonCode.AUTHORIZED));
    }

    public ExecutionContext createExecutionContext(PolicyDecision policyDecision, UUID businessTaskId,
                                                   String idempotencyKey, String businessOperationRef,
                                                   List<ExecutionContext.InputVersion> inputVersions,
                                                   boolean containsPii) {
        if (!policyDecision.allowsExecution()) {
            throw denied(policyDecision);
        }
        Instant now = Instant.now();
        String requestId = MDC.get("request_id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        return new ExecutionContext(UUID.randomUUID(), null, requestId, requestId,
                policyDecision.tenantId(), policyDecision.actorId(), businessTaskId,
                idempotencyKey, policyDecision.capability(), businessOperationRef,
                List.copyOf(inputVersions), policyDecision,
                new ExecutionContext.DataHandling(containsPii, "ephemeral", false), now);
    }

    private PolicyDecision decision(FlowCapability capability, TenantScope scope, UUID actorId,
                                    PolicyDecision.Decision outcome, List<PolicyDecision.ReasonCode> reasons) {
        return new PolicyDecision(UUID.randomUUID(), capability, outcome, reasons, scope.tenantId(),
                actorId, POLICY_VERSION, Instant.now());
    }

    private ApiException denied(PolicyDecision decision) {
        boolean insufficient = decision.reasonCodes().contains(PolicyDecision.ReasonCode.INSUFFICIENT_BALANCE);
        return new ApiException(insufficient ? "INSUFFICIENT_BALANCE" : "POLICY_DENIED",
                insufficient ? "可用余额不足，无法执行该 AI 能力" : "该 AI 能力尚未满足执行策略",
                HttpStatus.CONFLICT);
    }
}
