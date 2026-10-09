package com.intelligentrecruitment.agentflow.application;

import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/** Resolves a capability to a frozen internal Agent route at Attempt creation time. */
@Component
public final class RecruitmentRouteResolver {
    private static final Set<FlowCapability> DIRECT_CAPABILITIES = Set.of(
            FlowCapability.JD_GENERATION,
            FlowCapability.RESUME_PARSING,
            FlowCapability.CANDIDATE_SCREENING);
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");

    private final boolean complexEnabled;
    private final String routeConfigVersion;

    public RecruitmentRouteResolver(
            @Value("${app.recruitment-distribution.complex-agent-enabled:}") String enabled,
            @Value("${app.recruitment-distribution.route-config-version:}") String routeConfigVersion) {
        if (enabled == null || !("true".equalsIgnoreCase(enabled.trim()) || "false".equalsIgnoreCase(enabled.trim()))) {
            throw new IllegalStateException("RECRUITMENT_COMPLEX_AGENT_ENABLED must be explicitly true or false");
        }
        if (routeConfigVersion == null || !VERSION.matcher(routeConfigVersion.trim()).matches()) {
            throw new IllegalStateException("RECRUITMENT_ROUTE_CONFIG_VERSION is required and must be a valid version identifier");
        }
        this.complexEnabled = Boolean.parseBoolean(enabled.trim());
        this.routeConfigVersion = routeConfigVersion.trim();
    }

    public ExecutionContext.AgentRoute resolve(FlowCapability capability) {
        if (capability == null) throw new IllegalArgumentException("AI capability is required for route resolution");
        String operation = operation(capability);
        boolean useComplex = complexEnabled && DIRECT_CAPABILITIES.contains(capability);
        return new ExecutionContext.AgentRoute(
                useComplex ? "complex_recruitment_agent" : "simple_recruitment_agent",
                operation,
                routeConfigVersion,
                DIRECT_CAPABILITIES.contains(capability) ? "deployment_config" : "fixed_capability_rule");
    }

    private static String operation(FlowCapability capability) {
        return switch (capability) {
            case JD_GENERATION -> "create";
            case RESUME_PARSING -> "analyze";
            case CANDIDATE_SCREENING -> "match";
            case JD_IN_PLACE_REVISION -> "revise";
            case INTERVIEW_KIT_GENERATION -> "generate";
            case CONVERSATION_CONTINUE -> "continue";
            case REQUIREMENT_CHAT,CONVERSATION_ROUTE -> "route";
            default -> "execute";
        };
    }
}
