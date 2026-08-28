package com.intelligentrecruitment.aiplatform.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.RouteDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.ConversationAgentCommand;
import com.intelligentrecruitment.aiplatform.application.RouteAgentCommand;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.shared.error.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * A deliberately narrow, opt-in adapter for temporary JD and route testing.
 * It is not the production AI Platform and must not be enabled without an
 * approved external-data policy.
 */
@Component
@ConditionalOnProperty(name = "app.ai-platform.mode", havingValue = "deepseek")
public class DeepSeekAiPlatformClient implements AiPlatformClient {

    private static final String JSON_FORMAT = "json_object";

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final boolean allowExternalData;
    private final Resource jdPromptResource;
    private final Resource conversationPromptResource;
    private final Resource jdInPlaceRevisionPromptResource;
    private final Map<String, AiTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();
    private final Map<String, StructuredResult> results = new ConcurrentHashMap<>();

    public DeepSeekAiPlatformClient(RestClient.Builder builder, ObjectMapper objectMapper,
                                    @Value("${app.ai-platform.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
                                    @Value("${app.ai-platform.deepseek.api-key:}") String apiKey,
                                    @Value("${app.ai-platform.deepseek.model:deepseek-v4-flash}") String model,
                                    @Value("${app.ai-platform.deepseek.allow-external-data:false}") boolean allowExternalData,
                                    @Value("${app.ai-platform.deepseek.jd-prompt-resource:classpath:prompts/jd-generation-v1.txt}") Resource jdPromptResource,
                                    @Value("${app.ai-platform.deepseek.conversation-prompt-resource:classpath:prompts/recruitment-conversation-v1.txt}") Resource conversationPromptResource,
                                    @Value("${app.ai-platform.deepseek.jd-in-place-revision-prompt-resource:classpath:prompts/jd-in-place-revision-v1.txt}") Resource jdInPlaceRevisionPromptResource) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.allowExternalData = allowExternalData;
        this.jdPromptResource = jdPromptResource;
        this.conversationPromptResource = conversationPromptResource;
        this.jdInPlaceRevisionPromptResource = jdInPlaceRevisionPromptResource;
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command) {
        requireEnabled();
        String existingTaskId = idempotencyIndex.get(command.idempotencyKey());
        if (existingTaskId != null) return tasks.get(existingTaskId);
        if (command.capability() != AiCapability.JD_GENERATION) {
            throw unsupported(command.capability());
        }
        String aiTaskId = "deepseek_ait_" + UUID.randomUUID();
        StructuredResult result = generateJd(aiTaskId, command);
        AiTask task = new AiTask(aiTaskId, command.businessTaskId(), command.capability(), AiTaskStatus.COMPLETED,
                1, 1, 100, Instant.now());
        tasks.put(aiTaskId, task);
        results.put(aiTaskId, result);
        idempotencyIndex.put(command.idempotencyKey(), aiTaskId);
        return task;
    }

    @Override
    public AiTask getTask(String aiTaskId) {
        AiTask task = tasks.get(aiTaskId);
        if (task == null) throw taskNotFound();
        return task;
    }

    @Override
    public AiTask cancelTask(String aiTaskId, String idempotencyKey) {
        AiTask current = getTask(aiTaskId);
        if (current.status() == AiTaskStatus.COMPLETED || current.status() == AiTaskStatus.FAILED) return current;
        AiTask cancelled = new AiTask(current.aiTaskId(), current.businessTaskId(), current.capability(),
                AiTaskStatus.CANCELLED, current.completed(), current.total(), current.percent(), current.acceptedAt());
        tasks.put(aiTaskId, cancelled);
        return cancelled;
    }

    @Override
    public RouteDecision routeMessage(RouteAgentCommand command) {
        requireEnabled();
        String content = completeJson(routeSystemPrompt(command.allowedCapabilities()), command.message(), 500);
        JsonNode json = readJson(content);
        RouteDecision.Kind kind = routeKind(text(json, "kind"));
        FlowCapability capability = routeCapability(text(json, "capability"), command.allowedCapabilities());
        double confidence = json.path("confidence").isNumber() ? json.path("confidence").asDouble() : 0.5;
        confidence = Math.max(0, Math.min(1, confidence));
        String clarification = nullableText(json, "clarification");
        RouteDecision.SuggestedNextAction action = routeAction(text(json, "suggested_next_action"));
        return new RouteDecision(UUID.randomUUID(), kind, capability, confidence, true,
                strings(json.path("missing_inputs")), clarification, action, Instant.now());
    }

    @Override
    public String continueConversation(ConversationAgentCommand command) {
        requireEnabled();
        String answer = completeText(readPrompt(conversationPromptResource, "招聘对话 Prompt 模板不可用"), json(Map.of(
                "task_id", command.businessTaskId(), "messages", command.messages(), "current_jd", command.jdDraft())));
        if (answer.isBlank()) throw contractInvalid("DeepSeek 返回了空的对话回复");
        return answer.trim();
    }

    @Override
    public StructuredResult reviseJdInPlace(ConversationAgentCommand command) {
        requireEnabled();
        JsonNode node = readJson(completeJson(readPrompt(jdInPlaceRevisionPromptResource, "JD 修改 Prompt 模板不可用"),
                json(Map.of("messages", command.messages(), "current_jd", command.jdDraft())), 1800));
        Map<String, Object> data = objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
        requireText(data, "title"); requireText(data, "company_name"); requireText(data, "responsibilities");
        requireText(data, "requirements"); requireText(data, "skills"); requireText(data, "talent_profile");
        data.putIfAbsent("location", "工作地点待确认"); data.putIfAbsent("experience_level", "经验待确认");
        data.putIfAbsent("education", "学历待确认"); data.putIfAbsent("job_type", "全职"); data.putIfAbsent("warnings", List.of());
        String resultId = "deepseek_revision_" + UUID.randomUUID();
        return new StructuredResult(UUID.randomUUID(), resultId, FlowCapability.JD_GENERATION,
                StructuredResult.Status.DRAFT_READY, "jd-v1", data, List.of(), List.of(),
                new StructuredResult.Provenance("deepseek-jd", "v1", "deepseek-jd-in-place-v1", model),
                new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    @Override
    public StructuredResult getStructuredResult(String aiTaskId) {
        StructuredResult result = results.get(aiTaskId);
        if (result == null) throw taskNotFound();
        return result;
    }

    private StructuredResult generateJd(String aiTaskId, StartAiTaskCommand command) {
        if (command.executionContext() == null) {
            throw contractInvalid("JD 生成请求缺少 execution_context");
        }
        String content = completeJson(jdSystemPrompt(), json(command.input()), 1800);
        JsonNode node = readJson(content);
        Map<String, Object> data = objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
        requireText(data, "title");
        requireText(data, "company_name");
        requireText(data, "responsibilities");
        requireText(data, "requirements");
        requireText(data, "skills");
        requireText(data, "talent_profile");
        data.putIfAbsent("location", "工作地点待确认");
        data.putIfAbsent("experience_level", "经验待确认");
        data.putIfAbsent("education", "学历待确认");
        data.putIfAbsent("job_type", "全职");
        data.putIfAbsent("warnings", List.of());
        return new StructuredResult(command.executionContext().executionId(), aiTaskId, FlowCapability.JD_GENERATION,
                StructuredResult.Status.DRAFT_READY, "jd-v1", data, List.of(), List.of(),
                new StructuredResult.Provenance("deepseek-jd", "v1", "deepseek-jd-v1", model),
                new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    private String completeJson(String systemPrompt, String userPrompt, int maxTokens) {
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "response_format", Map.of("type", JSON_FORMAT),
                "stream", false,
                "max_tokens", maxTokens
        );
        try {
            String response = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(payload).retrieve().body(String.class);
            JsonNode root = readJson(response);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) throw contractInvalid("DeepSeek 返回了空的 JSON 内容");
            return content;
        } catch (RestClientException exception) {
            throw new ApiException("AI_PROVIDER_UNAVAILABLE", "DeepSeek 服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String completeText(String systemPrompt, String userPrompt) {
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "stream", false,
                "max_tokens", 800
        );
        try {
            String response = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(payload).retrieve().body(String.class);
            JsonNode root = readJson(response);
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (RestClientException exception) {
            throw new ApiException("AI_PROVIDER_UNAVAILABLE", "DeepSeek 服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private void requireEnabled() {
        if (!allowExternalData) {
            throw new ApiException("DATA_POLICY_BLOCKED", "DeepSeek 临时适配器未获外部数据处理授权", HttpStatus.FORBIDDEN);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException("AI_AUTH_FAILED", "未配置 DEEPSEEK_API_KEY", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String routeSystemPrompt(List<FlowCapability> capabilities) {
        return """
                You are a recruitment assistant router. Return only a valid json object; do not execute actions.
                Choose only a capability from this allow-list: %s.
                Required json fields: kind (route|clarify|inform|unsupported), capability (string or null),
                confidence (0 to 1), missing_inputs (string array), clarification (string or null),
                suggested_next_action (collect_requirement|select_job_version|select_candidates|prepare_screening_plan|show_quote|inspect_task|none).
                For candidate screening, do not infer a job version, candidate list, plan, budget, or permission.
                """.formatted(capabilities.stream().map(Enum::name).toList());
    }

    private String jdSystemPrompt() {
        return readPrompt(jdPromptResource, "JD Prompt 模板不可用");
    }

    private static String readPrompt(Resource resource, String errorMessage) {
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new ApiException("AI_PROMPT_UNAVAILABLE", errorMessage, HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private JsonNode readJson(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException exception) { throw contractInvalid("DeepSeek 返回的内容不是有效 JSON"); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw contractInvalid("无法构造 DeepSeek 请求"); }
    }

    private static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : ""; }
    private static String nullableText(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : null; }
    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(item -> { if (item.isTextual()) values.add(item.asText()); });
        return values;
    }
    private static RouteDecision.Kind routeKind(String value) {
        try { return RouteDecision.Kind.fromValue(value); }
        catch (IllegalArgumentException exception) { throw contractInvalid("DeepSeek 返回了无效的路由类型"); }
    }
    private static RouteDecision.SuggestedNextAction routeAction(String value) {
        try { return RouteDecision.SuggestedNextAction.fromValue(value); }
        catch (IllegalArgumentException exception) { return RouteDecision.SuggestedNextAction.NONE; }
    }
    private static FlowCapability routeCapability(String value, List<FlowCapability> allowed) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;
        try {
            FlowCapability capability = FlowCapability.fromValue(value.trim());
            if (!allowed.contains(capability)) throw contractInvalid("DeepSeek 返回了未授权的能力");
            return capability;
        } catch (IllegalArgumentException exception) {
            throw contractInvalid("DeepSeek 返回了未知能力");
        }
    }
    private static void requireText(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null || String.valueOf(value).isBlank()) throw contractInvalid("DeepSeek JD 缺少 " + field);
    }
    private static ApiException contractInvalid(String message) { return new ApiException("AI_CONTRACT_INVALID", message, HttpStatus.BAD_GATEWAY); }
    private static ApiException taskNotFound() { return new ApiException("AI_TASK_NOT_FOUND", "AI 任务不存在", HttpStatus.NOT_FOUND); }
    private static ApiException unsupported(AiCapability capability) { return new ApiException("AI_CAPABILITY_UNAVAILABLE", "DeepSeek 临时适配器暂只支持 JD 生成和意图路由：" + capability, HttpStatus.NOT_IMPLEMENTED); }
}
