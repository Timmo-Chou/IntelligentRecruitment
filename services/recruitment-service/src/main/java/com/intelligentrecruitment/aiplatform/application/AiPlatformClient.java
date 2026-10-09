package com.intelligentrecruitment.aiplatform.application;

import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import java.util.function.Consumer;

public interface AiPlatformClient {

    AiTask startTask(StartAiTaskCommand command);

    default AiTask startTask(StartAiTaskCommand command, Consumer<String> onDelta) {
        return startTask(command);
    }

    AiTask getTask(String aiTaskId, String actorId);

    AiTask cancelTask(String aiTaskId, String idempotencyKey, String actorId);

    RouteDecision routeMessage(RouteAgentCommand command);

    String continueConversation(ConversationAgentCommand command);

    StructuredResult reviseJdInPlace(ConversationAgentCommand command);

    StructuredResult getStructuredResult(String aiTaskId, String actorId);

    /** Called only after the consuming business transaction has durably saved the mapped result. */
    default void confirmResultPersisted(String aiTaskId, StructuredResult result) { }

    /** Fixed-price Agents settle only after the business mapper has verified and saved one billable unit. */
    default void confirmResultPersisted(String aiTaskId, StructuredResult result, int billableUnits,
                                        String validity, String reason) {
        confirmResultPersisted(aiTaskId, result);
    }

    /** A verified completed execution with no billable unit selects the one RELEASE decision. */
    default void confirmNoBillableResult(String aiTaskId, String reason) { }

    /** Keeps an accepted non-idempotent Direct call reserved while its outcome is reconciled. */
    default void holdForReconciliation(String aiTaskId, String reason) { }

}
