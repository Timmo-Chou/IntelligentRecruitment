package com.intelligentrecruitment.agentflow.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A non-authoritative recommendation produced by the AI Platform. */
public record RouteDecision(
        @JsonProperty("decision_id") UUID decisionId,
        Kind kind,
        FlowCapability capability,
        @JsonProperty("secondary_intent") String secondaryIntent,
        Operation operation,
        double confidence,
        @JsonProperty("requires_policy_check") boolean requiresPolicyCheck,
        @JsonProperty("missing_inputs") List<String> missingInputs,
        String clarification,
        @JsonProperty("suggested_next_action") SuggestedNextAction suggestedNextAction,
        @JsonProperty("created_at") Instant createdAt
) {
    /**
     * A bounded verb describing what the user asked to do.  It is intentionally
     * non-authoritative: business policy still decides whether the operation may
     * execute, require a quote, or require explicit confirmation.
     */
    public enum Operation {
        CREATE("create"), REVISE("revise"), CONTINUE("continue"), INSPECT("inspect"),
        CONFIRM("confirm"), CANCEL("cancel"), RETRY("retry");

        private final String value;
        Operation(String value) { this.value = value; }
        @JsonValue public String value() { return value; }
        @JsonCreator public static Operation fromValue(String value) {
            for (Operation operation : values()) if (operation.value.equals(value)) return operation;
            throw new IllegalArgumentException("Unknown route operation: " + value);
        }
    }

    public enum Kind {
        ROUTE("route"), CLARIFY("clarify"), INFORM("inform"), UNSUPPORTED("unsupported");
        private final String value;
        Kind(String value) { this.value = value; }
        @JsonValue public String value() { return value; }
        @JsonCreator public static Kind fromValue(String value) {
            for (Kind kind : values()) if (kind.value.equals(value)) return kind;
            throw new IllegalArgumentException("Unknown route kind: " + value);
        }
    }

    public enum SuggestedNextAction {
        COLLECT_REQUIREMENT("collect_requirement"),
        SELECT_JOB_VERSION("select_job_version"),
        SELECT_CANDIDATES("select_candidates"),
        PREPARE_SCREENING_PLAN("prepare_screening_plan"),
        SHOW_QUOTE("show_quote"),
        INSPECT_TASK("inspect_task"),
        NONE("none");
        private final String value;
        SuggestedNextAction(String value) { this.value = value; }
        @JsonValue public String value() { return value; }
        @JsonCreator public static SuggestedNextAction fromValue(String value) {
            for (SuggestedNextAction action : values()) if (action.value.equals(value)) return action;
            throw new IllegalArgumentException("Unknown route action: " + value);
        }
    }
}
