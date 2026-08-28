package com.intelligentrecruitment.agentflow.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PolicyDecision(
        @JsonProperty("policy_decision_id") UUID policyDecisionId,
        FlowCapability capability,
        Decision decision,
        @JsonProperty("reason_codes") List<ReasonCode> reasonCodes,
        @JsonProperty("workspace_id") UUID workspaceId,
        @JsonProperty("company_id") UUID companyId,
        @JsonProperty("actor_id") UUID actorId,
        Confirmation confirmation,
        @JsonProperty("policy_version") String policyVersion,
        @JsonProperty("evaluated_at") Instant evaluatedAt
) {
    public boolean allowsExecution() {
        return decision == Decision.ALLOW;
    }

    public enum Decision {
        ALLOW("allow"),
        REQUIRE_USER_CONFIRMATION("require_user_confirmation"),
        DENY("deny");

        private final String value;

        Decision(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        @JsonCreator
        public static Decision fromValue(String value) {
            for (Decision candidate : values()) if (candidate.value.equals(value)) return candidate;
            throw new IllegalArgumentException("Unknown policy decision: " + value);
        }
    }

    public enum ReasonCode {
        AUTHORIZED,
        MISSING_JOB_VERSION,
        MISSING_CANDIDATE_SCOPE,
        MISSING_SCREENING_PLAN,
        MISSING_SCREENING_RESULT,
        INSUFFICIENT_BALANCE,
        QUOTE_REQUIRED,
        QUOTE_EXPIRED,
        USER_CONFIRMATION_REQUIRED,
        SENSITIVE_RULE_BLOCKED,
        DATA_POLICY_BLOCKED,
        RESOURCE_NOT_FOUND,
        INVALID_STATE
    }

    public record Confirmation(
            @JsonProperty("quote_id") UUID quoteId,
            @JsonProperty("estimated_amount_minor") long estimatedAmountMinor,
            String currency,
            @JsonProperty("confirmed_at") Instant confirmedAt,
            @JsonProperty("confirmed_by") UUID confirmedBy
    ) {
    }
}
