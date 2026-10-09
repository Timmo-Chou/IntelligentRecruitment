package com.intelligentrecruitment.agentflow.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExecutionContext(
        @JsonProperty("execution_id") UUID executionId,
        @JsonProperty("route_decision_id") UUID routeDecisionId,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("trace_id") String traceId,
        @JsonProperty("tenant_id") UUID tenantId,
        @JsonProperty("actor_id") UUID actorId,
        @JsonProperty("business_task_id") UUID businessTaskId,
        @JsonProperty("idempotency_key") String idempotencyKey,
        FlowCapability capability,
        @JsonProperty("business_operation_ref") String businessOperationRef,
        @JsonProperty("input_versions") List<InputVersion> inputVersions,
        @JsonProperty("policy_decision") PolicyDecision policyDecision,
        @JsonProperty("data_handling") DataHandling dataHandling,
        @JsonProperty("requested_at") Instant requestedAt,
        @JsonProperty("agent_route") AgentRoute agentRoute
) {
    public ExecutionContext(UUID executionId, UUID routeDecisionId, String requestId, String traceId,
                            UUID tenantId, UUID actorId, UUID businessTaskId, String idempotencyKey,
                            FlowCapability capability, String businessOperationRef,
                            List<InputVersion> inputVersions, PolicyDecision policyDecision,
                            DataHandling dataHandling, Instant requestedAt) {
        this(executionId, routeDecisionId, requestId, traceId, tenantId, actorId, businessTaskId,
                idempotencyKey, capability, businessOperationRef, inputVersions, policyDecision,
                dataHandling, requestedAt, null);
    }

    public record InputVersion(String kind, String ref, String version, @JsonProperty("content_hash") String contentHash) {
    }

    public record AgentRoute(@JsonProperty("agent_id") String agentId,
                             String operation,
                             @JsonProperty("route_config_version") String routeConfigVersion,
                             @JsonProperty("selection_source") String selectionSource) { }

    public record DataHandling(@JsonProperty("contains_pii") boolean containsPii,
                               String retention,
                               @JsonProperty("log_content") boolean logContent) {
    }
}
