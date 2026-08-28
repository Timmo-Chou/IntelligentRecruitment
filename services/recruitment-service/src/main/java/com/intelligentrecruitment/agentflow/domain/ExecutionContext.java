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
        @JsonProperty("workspace_id") UUID workspaceId,
        @JsonProperty("company_id") UUID companyId,
        @JsonProperty("actor_id") UUID actorId,
        @JsonProperty("business_task_id") UUID businessTaskId,
        @JsonProperty("idempotency_key") String idempotencyKey,
        FlowCapability capability,
        @JsonProperty("business_operation_ref") String businessOperationRef,
        @JsonProperty("input_versions") List<InputVersion> inputVersions,
        @JsonProperty("policy_decision") PolicyDecision policyDecision,
        @JsonProperty("data_handling") DataHandling dataHandling,
        @JsonProperty("requested_at") Instant requestedAt
) {
    public record InputVersion(String kind, String ref, String version, @JsonProperty("content_hash") String contentHash) {
    }

    public record DataHandling(@JsonProperty("contains_pii") boolean containsPii,
                               String retention,
                               @JsonProperty("log_content") boolean logContent) {
    }
}
