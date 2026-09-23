package com.intelligentrecruitment.aiplatform.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.aiplatform.application.*;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通过 HTTP 调用 AIAgentPlatform 的客户端（对内面 /api/v1/*）。
 * 所有招聘 AI 能力均由 AIAgentPlatform 执行；BOSS 在 Agent 接收任务前完成
 * 授权和积分预占，并在任务完成后接收用量上报与结算。
 */
@Component
public class HttpAiPlatformClient implements AiPlatformClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiPlatformClient.class);

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final BossControlPlaneClient boss;
    private final String serviceToken;
    private final AiExecutionLedger ledger;
    private final Map<String, BossControlPlaneClient.AiAuthorization> authorizations = new ConcurrentHashMap<>();
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();

    public HttpAiPlatformClient(RestClient.Builder builder, ObjectMapper objectMapper,
                                @Value("${app.ai-platform.http.base-url:http://localhost:8083}") String baseUrl,
                                BossControlPlaneClient boss,
                                @Value("${app.ai-platform.http.service-token:}") String serviceToken,
                                AiExecutionLedger ledger) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceFirst("/+$", "");
        this.boss = boss;
        this.serviceToken = serviceToken;
        this.ledger = ledger;
    }

    /** BOSS-driven lifecycle cleanup; AIAgent retains accounting metadata but erases business payloads. */
    public void logicallyDeleteTenantBusinessData(java.util.UUID tenantId) {
        client.post().uri("/api/v1/internal/tenants/{tenantId}/logical-delete", tenantId)
                .header("Authorization", "Bearer " + boss.internalAccessToken()).retrieve().toBodilessEntity();
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command) {
        ExecutionContext execution = command.executionContext();
        if (execution == null || execution.policyDecision() == null
                || !execution.policyDecision().allowsExecution()) {
            throw new IllegalStateException("调用 AIAgentPlatform 前必须完成 BOSS PolicyDecision=allow");
        }
        if (execution.tenantId() == null || execution.actorId() == null) {
            throw new IllegalStateException("AIAgentPlatform ExecutionContext 必须包含 tenant_id 与 actor_id");
        }
        if (!execution.tenantId().toString().equals(command.tenantId())
                || !execution.actorId().toString().equals(command.actorId())
                || !execution.businessTaskId().toString().equals(command.businessTaskId())
                || !execution.idempotencyKey().equals(command.idempotencyKey())) {
            throw new IllegalStateException("AI 命令与 ExecutionContext 的 Tenant、用户、业务任务或幂等键不一致");
        }
        Map<String, Object> context = Map.of(
                "request_id", execution.requestId(),
                "trace_id", execution.traceId(),
                "tenant_id", execution.tenantId(),
                "actor_id", execution.actorId(),
                "business_task_id", execution.businessTaskId(),
                "idempotency_key", execution.idempotencyKey(),
                "locale", "zh-CN", "timezone", "Asia/Shanghai", "contract_version", "v1");
        Map<String, Object> body = Map.of(
                "execution_id", execution.executionId().toString(),
                "request_context", context,
                "capability", command.capability().name().toLowerCase(),
                "business_operation_ref", execution.businessOperationRef(),
                "input_versions", execution.inputVersions() == null ? List.of() : execution.inputVersions(),
                "policy_decision", execution.policyDecision(),
                "data_handling", execution.dataHandling(),
                "requested_at", execution.requestedAt(),
                "input", command.input());

        BossControlPlaneClient.AiAuthorization authorization = null;
        try {
            authorization = boss.authorizeAiExecution(execution.tenantId(), execution.actorId(),
                    execution.businessTaskId().toString(), command.capability().name(), "RECRUITMENT", command.idempotencyKey());
            ledger.authorized(execution.executionId(), execution.tenantId(), execution.actorId(), execution.businessTaskId().toString(), command.capability().name(), command.idempotencyKey(), authorization);
            String raw = client.post()
                    .uri("/api/v1/capability-executions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", command.idempotencyKey())
                    .header("X-Request-Id", execution.requestId())
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("X-AI-Grant", authorization.grant())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(raw);
            AiTask result = toAiTask(node, command.capability());
            authorizations.put(result.aiTaskId(), authorization);
            ledger.agentAccepted(execution.tenantId(), command.idempotencyKey(), result.aiTaskId());
            return result;
        } catch (Exception ex) {
            if (authorization != null) {
                try { ledger.enqueueCancellation(authorization); }
                catch (RuntimeException releaseFailure) { ex.addSuppressed(releaseFailure); }
            }
            log.error("调用 AIAgentPlatform 失败 capability={}", command.capability(), ex);
            throw new RuntimeException("AIAgentPlatform 调用失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public AiTask getTask(String aiTaskId, String actorId) {
        try {
            String raw = client.get()
                    .uri("/api/v1/tasks/{id}", aiTaskId)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("X-IR-Actor-Id", requiredActor(actorId))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(raw);
            AiTask result = toAiTask(node, AiCapability.JD_GENERATION);
            retryCounts.put(aiTaskId, result.retryCount());
            ledger.authorizationForAgentTask(aiTaskId)
                    .map(BossControlPlaneClient.AiAuthorization::tenantId)
                    .ifPresent(tenantId -> ledger.recordRetryCount(tenantId, aiTaskId, result.retryCount()));
            if (result.status() == AiTaskStatus.FAILED || result.status() == AiTaskStatus.CANCELLED) {
                BossControlPlaneClient.AiAuthorization authorization = authorizations.get(aiTaskId);
                if (authorization == null) authorization = ledger.authorizationForAgentTask(aiTaskId).orElse(null);
                if (authorization != null) ledger.enqueueCancellation(authorization);
            }
            return result;
        } catch (Exception ex) {
            log.error("查询 AIAgentPlatform 任务失败 id={}", aiTaskId, ex);
            throw new RuntimeException("AIAgentPlatform 任务查询失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public AiTask cancelTask(String aiTaskId, String idempotencyKey, String actorId) {
        try {
            String raw = client.post()
                    .uri("/api/v1/tasks/{id}/cancel", aiTaskId)
                    .header("Idempotency-Key", idempotencyKey)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("X-IR-Actor-Id", requiredActor(actorId))
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(raw);
            AiTask result = toAiTask(node, AiCapability.JD_GENERATION);
            BossControlPlaneClient.AiAuthorization authorization = authorizations.get(aiTaskId);
            if (authorization == null) authorization = ledger.authorizationForAgentTask(aiTaskId).orElse(null);
            if (authorization != null) ledger.enqueueCancellation(authorization);
            return result;
        } catch (Exception ex) {
            log.error("取消 AIAgentPlatform 任务失败 id={}", aiTaskId, ex);
            throw new RuntimeException("AIAgentPlatform 任务取消失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public StructuredResult getStructuredResult(String aiTaskId, String actorId) {
        try {
            String raw = client.get()
                    .uri("/api/v1/tasks/{id}/result", aiTaskId)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("X-IR-Actor-Id", requiredActor(actorId))
                    .retrieve()
                    .body(String.class);
            StructuredResult result = objectMapper.readValue(raw, StructuredResult.class);
            BossControlPlaneClient.AiAuthorization authorization = authorizations.get(aiTaskId);
            if (authorization == null) authorization = ledger.authorizationForAgentTask(aiTaskId).orElse(null);
            if (authorization != null) {
                var usage = result.usage();
                String model = result.provenance() == null || result.provenance().modelId() == null
                        ? authorization.modelId() : result.provenance().modelId();
                int retryCount = ledger.retryCountForAgentTask(authorization.tenantId(), aiTaskId);
                ledger.enqueueUsage(authorization.idempotencyKey(), authorization, "SUCCEEDED", model, usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(), retryCount, aiTaskId);
                authorizations.remove(aiTaskId, authorization);
                retryCounts.remove(aiTaskId);
            }
            return result;
        } catch (Exception ex) {
            log.error("获取 AIAgentPlatform 任务结果失败 id={}", aiTaskId, ex);
            throw new RuntimeException("AIAgentPlatform 结果获取失败: " + ex.getMessage(), ex);
        }
    }

    @Override
    public RouteDecision routeMessage(RouteAgentCommand command) {
        if (command.tenantId() == null || command.actorId() == null) {
            throw new IllegalStateException("agent-routes 必须提供 BOSS tenant_id 与 actor_id");
        }
        Map<String, Object> body = Map.of(
                "context", Map.of("request_id", command.requestId(), "trace_id", command.traceId(),
                        "tenant_id", command.tenantId(), "actor_id", command.actorId(),
                        "business_task_id", command.businessTaskId(), "idempotency_key", command.idempotencyKey(), "locale", "zh-CN",
                        "timezone", "Asia/Shanghai", "contract_version", "v1"),
                "message", command.message(),
                "allowed_capabilities", command.allowedCapabilities() == null ? List.of()
                        : command.allowedCapabilities().stream().map(FlowCapability::value).toList());
        BossControlPlaneClient.AiAuthorization routeAuthorization = null;
        try {
            BossControlPlaneClient.AiAuthorization authorization = boss.authorizeAiExecution(UUID.fromString(command.tenantId()),
                    UUID.fromString(command.actorId()), command.businessTaskId(), "CONVERSATION_ROUTE", "RECRUITMENT", command.idempotencyKey());
            routeAuthorization = authorization;
            ledger.authorized(UUID.randomUUID(), UUID.fromString(command.tenantId()), UUID.fromString(command.actorId()), command.businessTaskId(), "CONVERSATION_ROUTE", command.idempotencyKey(), authorization);
            String raw = client.post().uri("/api/v1/agent-routes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + serviceToken)
                    .header("X-AI-Grant", authorization.grant())
                    .header("X-Request-Id", command.requestId())
                    .body(body).retrieve().body(String.class);
            Map<String, Object> response = objectMapper.readValue(raw, Map.class);
            Map<String, Object> usage = response.get("usage") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            String model = response.get("model_id") == null ? authorization.modelId() : String.valueOf(response.get("model_id"));
            int inputTokens = number(usage.get("input_tokens"));
            int outputTokens = number(usage.get("output_tokens"));
            RouteDecision decision = parseRouteDecision(response, command);
            ledger.enqueueUsage(authorization.idempotencyKey(), authorization, "SUCCEEDED", model, inputTokens, outputTokens, 0, command.businessTaskId());
            return decision;
        } catch (Exception ex) {
            if (routeAuthorization != null) {
                try { ledger.enqueueCancellation(routeAuthorization); }
                catch (RuntimeException ignored) { }
            }
            throw new RuntimeException("AIAgentPlatform 路由失败: " + ex.getMessage(), ex);
        }
    }

    private static int number(Object value) {
        return value instanceof Number n ? Math.max(0, n.intValue()) : 0;
    }

    @Override
    public String continueConversation(ConversationAgentCommand command) {
        StructuredResult result = startAndAwait(command, AiCapability.CONVERSATION_CONTINUE,
                Map.of("conversation", command.messages(), "current_jd", command.jdDraft()));
        String message = result.data() == null ? "" : String.valueOf(result.data().getOrDefault("message", "")).trim();
        if (message.isBlank()) throw new IllegalStateException("对话能力未返回有效回复");
        return message;
    }

    @Override
    public StructuredResult reviseJdInPlace(ConversationAgentCommand command) {
        return startAndAwait(command, AiCapability.JD_IN_PLACE_REVISION,
                Map.of("conversation", command.messages(), "current_jd", command.jdDraft()));
    }

    private StructuredResult startAndAwait(ConversationAgentCommand command, AiCapability capability,
                                           Map<String, Object> input) {
        if (command.executionContext() == null) {
            throw new IllegalArgumentException("对话能力必须携带 BOSS 授权执行上下文");
        }
        AiTask task = startTask(new StartAiTaskCommand(command.tenantId(), command.actorId(),
                command.businessTaskId(), command.executionContext().idempotencyKey(), capability, input,
                command.executionContext()));
        for (int attempt = 0; attempt < 120; attempt++) {
            AiTask current = getTask(task.aiTaskId(), command.actorId());
            if (current.status() == AiTaskStatus.COMPLETED) {
                return getStructuredResult(current.aiTaskId(), command.actorId());
            }
            if (current.status() == AiTaskStatus.FAILED || current.status() == AiTaskStatus.CANCELLED) {
                throw new IllegalStateException(current.errorMessage() == null ? "AI 任务执行失败" : current.errorMessage());
            }
            try { Thread.sleep(250L); }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 AI 任务完成时被中断", exception);
            }
        }
        throw new IllegalStateException("AI 任务仍在执行，请稍后重试");
    }

    // ==================== 结果解析 ====================

    @SuppressWarnings("unchecked")
    private RouteDecision parseRouteDecision(Map<String, Object> data, RouteAgentCommand command) {
        String kindStr = String.valueOf(data.getOrDefault("kind", "FALLBACK"));
        RouteDecision.Kind kind;
        try {
            kind = RouteDecision.Kind.fromValue(kindStr);
        } catch (Exception e) {
            kind = RouteDecision.Kind.FALLBACK;
        }

        String capStr = String.valueOf(data.getOrDefault("capability", ""));
        com.intelligentrecruitment.agentflow.domain.FlowCapability capability = null;
        if (!capStr.isBlank() && command.allowedCapabilities() != null) {
            try {
                capability = com.intelligentrecruitment.agentflow.domain.FlowCapability.fromValue(capStr.trim());
                if (!command.allowedCapabilities().contains(capability)) capability = null;
            } catch (Exception ignored) {}
        }

        String secondaryIntent = data.get("secondary_intent") == null ? null : String.valueOf(data.get("secondary_intent"));
        String opStr = data.get("operation") == null ? null : String.valueOf(data.get("operation"));
        RouteDecision.Operation operation = null;
        if (opStr != null && !opStr.isBlank() && !"null".equalsIgnoreCase(opStr)) {
            try { operation = RouteDecision.Operation.fromValue(opStr); } catch (Exception ignored) {}
        }

        double confidence = 0.5;
        Object confObj = data.get("confidence");
        if (confObj instanceof Number n) confidence = Math.max(0, Math.min(1, n.doubleValue()));

        String clarification = data.get("clarification") == null ? null : String.valueOf(data.get("clarification"));

        List<String> missingInputs = new ArrayList<>();
        Object mi = data.get("missing_inputs");
        if (mi instanceof List<?> list) {
            for (Object item : list) if (item != null) missingInputs.add(String.valueOf(item));
        }

        String actionStr = String.valueOf(data.getOrDefault("suggested_next_action", "NONE"));
        RouteDecision.SuggestedNextAction action;
        try {
            action = RouteDecision.SuggestedNextAction.fromValue(actionStr);
        } catch (Exception e) {
            action = RouteDecision.SuggestedNextAction.NONE;
        }

        return new RouteDecision(java.util.UUID.randomUUID(), kind, capability, secondaryIntent, operation,
                confidence, true, missingInputs, clarification, action, Instant.now());
    }

    // ==================== 映射辅助 ====================

    private AiTask toAiTask(JsonNode node, AiCapability capability) {
        String id = node.path("ai_task_id").asText();
        String businessTaskId = node.path("business_task_id").asText();
        AiTaskStatus status = mapStatus(node.path("status").asText());
        int completed = node.path("progress").path("completed").asInt(0);
        int total = node.path("progress").path("total").asInt(1);
        int percent = node.path("progress").path("percent").asInt(0);
        int retryCount = node.path("retry_count").asInt(0);
        Instant acceptedAt = Instant.parse(node.path("accepted_at").asText(Instant.now().toString()));
        String errorCode = node.path("error").path("code").asText(null);
        String errorMessage = node.path("error").path("message").asText(null);
        return new AiTask(id, businessTaskId, capability, status, completed, total, percent, retryCount,
                acceptedAt, errorCode, errorMessage);
    }

    private AiTaskStatus mapStatus(String s) {
        return switch (s) {
            case "queued" -> AiTaskStatus.QUEUED;
            case "running" -> AiTaskStatus.RUNNING;
            case "waiting_for_input" -> AiTaskStatus.WAITING_FOR_INPUT;
            case "partially_completed" -> AiTaskStatus.PARTIALLY_COMPLETED;
            case "succeeded", "completed" -> AiTaskStatus.COMPLETED;
            case "failed" -> AiTaskStatus.FAILED;
            case "cancelled" -> AiTaskStatus.CANCELLED;
            default -> AiTaskStatus.RUNNING;
        };
    }

    private static String requiredActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("访问 AIAgentPlatform 任务必须提供 BOSS actor_id");
        }
        return actorId;
    }
}
