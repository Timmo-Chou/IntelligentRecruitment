package com.intelligentrecruitment.agentflow.application;

import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.PolicyDecision;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService.WorkspaceScope;
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
    private static final String CURRENCY = "CNY";

    public PolicyDecision evaluate(FlowCapability capability, WorkspaceScope scope, UUID actorId,
                                   long availableAmountMinor, long estimatedAmountMinor, UUID quoteId,
                                   boolean userConfirmed) {
        if (availableAmountMinor < estimatedAmountMinor) {
            return decision(capability, scope, actorId, PolicyDecision.Decision.DENY,
                    List.of(PolicyDecision.ReasonCode.INSUFFICIENT_BALANCE), quoteId,
                    estimatedAmountMinor, null);
        }
        if (!userConfirmed) {
            return decision(capability, scope, actorId, PolicyDecision.Decision.REQUIRE_USER_CONFIRMATION,
                    List.of(PolicyDecision.ReasonCode.AUTHORIZED,
                            PolicyDecision.ReasonCode.USER_CONFIRMATION_REQUIRED), quoteId,
                    estimatedAmountMinor, null);
        }
        return decision(capability, scope, actorId, PolicyDecision.Decision.ALLOW,
                List.of(PolicyDecision.ReasonCode.AUTHORIZED), quoteId, estimatedAmountMinor, Instant.now());
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
                policyDecision.workspaceId(), policyDecision.companyId(), policyDecision.actorId(), businessTaskId,
                idempotencyKey, policyDecision.capability(), businessOperationRef,
                List.copyOf(inputVersions), policyDecision,
                new ExecutionContext.DataHandling(containsPii, "ephemeral", false), now);
    }

    private PolicyDecision decision(FlowCapability capability, WorkspaceScope scope, UUID actorId,
                                    PolicyDecision.Decision outcome, List<PolicyDecision.ReasonCode> reasons,
                                    UUID quoteId, long estimatedAmountMinor, Instant confirmedAt) {
        PolicyDecision.Confirmation confirmation = new PolicyDecision.Confirmation(quoteId,
                estimatedAmountMinor, CURRENCY, confirmedAt, confirmedAt == null ? null : actorId);
        return new PolicyDecision(UUID.randomUUID(), capability, outcome, reasons, scope.workspaceId(),
                scope.companyId(), actorId, confirmation, POLICY_VERSION, Instant.now());
    }

    private ApiException denied(PolicyDecision decision) {
        boolean insufficient = decision.reasonCodes().contains(PolicyDecision.ReasonCode.INSUFFICIENT_BALANCE);
        return new ApiException(insufficient ? "INSUFFICIENT_BALANCE" : "POLICY_DENIED",
                insufficient ? "可用余额不足，无法执行该 AI 能力" : "该 AI 能力尚未满足执行策略",
                HttpStatus.CONFLICT);
    }
}
