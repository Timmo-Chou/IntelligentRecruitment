package com.intelligentrecruitment.agentflow.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum FlowCapability {
    REQUIREMENT_CHAT("requirement_chat"),
    JD_GENERATION("jd_generation"),
    RESUME_PARSING("resume_parsing"),
    SCREENING_PLAN_GENERATION("screening_plan_generation"),
    CANDIDATE_SCREENING("candidate_screening"),
    INTERVIEW_KIT_GENERATION("interview_kit_generation"),
    TASK_ASSISTANCE("task_assistance");

    private final String value;

    FlowCapability(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static FlowCapability fromValue(String value) {
        if (value == null) throw new IllegalArgumentException("Flow capability is required");
        for (FlowCapability capability : values()) {
            if (capability.value.equalsIgnoreCase(value) || capability.name().equalsIgnoreCase(value)) {
                return capability;
            }
        }
        throw new IllegalArgumentException("Unknown flow capability: " + value.toLowerCase(Locale.ROOT));
    }
}
