package com.intelligentrecruitment.aiplatform.application;

import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import java.util.List;

/** Server-to-server only input for a free-form message routing request. */
public record RouteAgentCommand(
        String requestId,
        String traceId,
        String workspaceId,
        String companyId,
        String actorId,
        String businessTaskId,
        String message,
        List<FlowCapability> allowedCapabilities
) {
}
