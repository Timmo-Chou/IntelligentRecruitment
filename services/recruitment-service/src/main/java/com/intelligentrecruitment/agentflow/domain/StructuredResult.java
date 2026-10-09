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
        @JsonProperty("generated_at") Instant generatedAt,
        @JsonProperty("result_contract_version")String resultContractVersion,@JsonProperty("attempt_id")UUID attemptId,
        @JsonProperty("tenant_id")UUID tenantId,@JsonProperty("agent_id")String agentId,String operation,
        @JsonProperty("input_hash")String inputHash,@JsonProperty("route_config_version")String routeConfigVersion,
        @JsonProperty("agent_adapter_version")String agentAdapterVersion,@JsonProperty("external_contract_version")String externalContractVersion,
        @JsonProperty("schema_version")String schemaVersion,@JsonProperty("provider_result_ref")String providerResultRef
) {
    public StructuredResult(UUID executionId,String aiTaskId,FlowCapability capability,Status status,String outputSchemaVersion,Map<String,Object> data,List<String>warnings,List<String>missingInformation,Provenance provenance,Usage usage,Instant generatedAt){
        this(executionId,aiTaskId,capability,status,outputSchemaVersion,data,warnings,missingInformation,provenance,usage,generatedAt,null,null,null,null,null,null,null,null,null,null,null);
    }
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
                             @JsonProperty("model_policy_version") String modelPolicyVersion,
                             @JsonProperty("model_id") String modelId,
                             @JsonProperty("tokenizer_id") String tokenizerId) {
        public Provenance(String skillId, String skillVersion, String promptVersion, String modelPolicyVersion) {
            this(skillId, skillVersion, promptVersion, modelPolicyVersion, null, null);
        }
    }

    public record Usage(@JsonProperty("input_tokens") int inputTokens,
                        @JsonProperty("output_tokens") int outputTokens,
                        @JsonProperty("supplier_cost_minor") long supplierCostMinor,
                        String currency) {
    }
}
