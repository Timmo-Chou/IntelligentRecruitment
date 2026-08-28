package com.intelligentrecruitment.agentflow.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A capability result envelope. Business services still validate and persist it. */
public record StructuredResult(
        @JsonProperty("execution_id") UUID executionId,
        @JsonProperty("ai_task_id") String aiTaskId,
        FlowCapability capability,
        Status status,
        @JsonProperty("output_schema_version") String outputSchemaVersion,
        Map<String, Object> data,
        List<String> warnings,
        @JsonProperty("missing_information") List<String> missingInformation,
        Provenance provenance,
        Usage usage,
        @JsonProperty("generated_at") Instant generatedAt
) {
    public enum Status {
        DRAFT_READY("draft_ready"), COMPLETED("completed"), PARTIALLY_COMPLETED("partially_completed"),
        WAITING_FOR_INPUT("waiting_for_input"), FAILED("failed");
        private final String value;
        Status(String value) { this.value = value; }
        @JsonValue public String value() { return value; }
        @JsonCreator public static Status fromValue(String value) {
            for (Status status : values()) if (status.value.equals(value)) return status;
            throw new IllegalArgumentException("Unknown structured result status: " + value);
        }
    }

    public record Provenance(@JsonProperty("skill_id") String skillId,
                             @JsonProperty("skill_version") String skillVersion,
                             @JsonProperty("prompt_version") String promptVersion,
                             @JsonProperty("model_policy_version") String modelPolicyVersion) {
    }

    public record Usage(@JsonProperty("input_tokens") int inputTokens,
                        @JsonProperty("output_tokens") int outputTokens,
                        @JsonProperty("supplier_cost_minor") long supplierCostMinor,
                        String currency) {
    }
}
