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
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.Competency;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.GenerateInterviewQuestionsInput;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.InterviewQuestionKit;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.Question;
import com.intelligentrecruitment.aiplatform.application.RecruitmentAssistantIntentCatalog;
import com.intelligentrecruitment.aiplatform.application.RouteAgentCommand;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.shared.error.ApiException;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
public class DeepSeekAiPlatformClient implements AiPlatformClient {

    private static final String JSON_FORMAT = "json_object";
    // JD 通常包含多个长列表；关闭思考模式，让输出额度全部用于可解析的业务 JSON。
    private static final int JD_MAX_TOKENS = 4096;
    // 简历解析要输出 7 段中文 markdown 长报告，2400 token 极易被截断导致 JSON 不完整，放宽到 8192。
    private static final int RESUME_PARSE_MAX_TOKENS = 8192;
    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiPlatformClient.class);

    private final RestClient client;
    private final HttpClient streamingClient;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final boolean allowExternalData;
    private final Resource jdPromptResource;
    private final Resource conversationPromptResource;
    private final Resource jdInPlaceRevisionPromptResource;
    private final Resource candidateScreeningPromptResource;
    private final Resource resumeParsingPromptResource;
    private final Resource interviewQuestionPromptResource;
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
                                    @Value("${app.ai-platform.deepseek.jd-in-place-revision-prompt-resource:classpath:prompts/jd-in-place-revision-v1.txt}") Resource jdInPlaceRevisionPromptResource,
                                    @Value("${app.ai-platform.deepseek.candidate-screening-prompt-resource:classpath:prompts/candidate-screening-v1.txt}") Resource candidateScreeningPromptResource,
                                    @Value("${app.ai-platform.deepseek.resume-parsing-prompt-resource:classpath:prompts/resume-parsing-v1.txt}") Resource resumeParsingPromptResource,
                                    @Value("${app.ai-platform.deepseek.interview-question-prompt-resource:classpath:prompts/interview-question-v1.txt}") Resource interviewQuestionPromptResource) {
        this.client = builder.baseUrl(baseUrl).build();
        this.streamingClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.baseUrl = baseUrl.replaceFirst("/+$", "");
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.allowExternalData = allowExternalData;
        this.jdPromptResource = jdPromptResource;
        this.conversationPromptResource = conversationPromptResource;
        this.jdInPlaceRevisionPromptResource = jdInPlaceRevisionPromptResource;
        this.candidateScreeningPromptResource = candidateScreeningPromptResource;
        this.resumeParsingPromptResource = resumeParsingPromptResource;
        this.interviewQuestionPromptResource = interviewQuestionPromptResource;
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command) {
        return startTask(command, ignored -> { });
    }

    @Override
    public AiTask startTask(StartAiTaskCommand command, Consumer<String> onDelta) {
        requireEnabled();
        String existingTaskId = idempotencyIndex.get(command.idempotencyKey());
        if (existingTaskId != null) return tasks.get(existingTaskId);
        if (command.capability() != AiCapability.JD_GENERATION
                && command.capability() != AiCapability.CANDIDATE_SCREENING
                && command.capability() != AiCapability.RESUME_PARSING) {
            throw unsupported(command.capability());
        }
        String aiTaskId = "deepseek_ait_" + UUID.randomUUID();
        AiTask task = new AiTask(aiTaskId, command.businessTaskId(), command.capability(), AiTaskStatus.RUNNING,
                0, 1, 0, Instant.now(), null, null);
        tasks.put(aiTaskId, task);
        idempotencyIndex.put(command.idempotencyKey(), aiTaskId);
        CompletableFuture.runAsync(() -> {
            try {
                StructuredResult result;
                if (command.capability() == AiCapability.JD_GENERATION) {
                    result = generateJdStreaming(aiTaskId, command, onDelta);
                } else if (command.capability() == AiCapability.RESUME_PARSING) {
                    result = generateResumeParse(aiTaskId, command);
                } else {
                    result = generateCandidateScreening(aiTaskId, command);
                }
                results.put(aiTaskId, result);
                tasks.put(aiTaskId, new AiTask(aiTaskId, command.businessTaskId(), command.capability(), AiTaskStatus.COMPLETED,
                        1, 1, 100, task.acceptedAt(), null, null));
            } catch (RuntimeException exception) {
                ProviderFailure failure = safeFailure(exception);
                log.warn("DeepSeek task failed, taskId={}, capability={}, code={}, cause={}", aiTaskId,
                        command.capability(), failure.code(), exception.getClass().getSimpleName());
                tasks.put(aiTaskId, new AiTask(aiTaskId, command.businessTaskId(), command.capability(), AiTaskStatus.FAILED,
                        0, 1, 100, task.acceptedAt(), failure.code(), failure.safeMessage()));
            }
        });
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
                AiTaskStatus.CANCELLED, current.completed(), current.total(), current.percent(), current.acceptedAt(),
                current.errorCode(), current.errorMessage());
        tasks.put(aiTaskId, cancelled);
        return cancelled;
    }

    @Override
    public RouteDecision routeMessage(RouteAgentCommand command) {
        requireEnabled();
        String content = completeJson(RecruitmentAssistantIntentCatalog.routerPrompt(command.allowedCapabilities()), command.message(), 700);
        JsonNode json = readJson(content);
        RouteDecision.Kind kind = routeKind(text(json, "kind"));
        FlowCapability capability = routeCapability(text(json, "capability"), command.allowedCapabilities());
        String secondaryIntent = nullableText(json, "secondary_intent");
        RouteDecision.Operation operation = routeOperation(text(json, "operation"));
        if (kind == RouteDecision.Kind.ROUTE
                && RecruitmentAssistantIntentCatalog.resolve(capability, secondaryIntent, operation, command.allowedCapabilities()) == null) {
            throw contractInvalid("DeepSeek 返回了未注册的二级意图或动作语义");
        }
        double confidence = json.path("confidence").isNumber() ? json.path("confidence").asDouble() : 0.5;
        confidence = Math.max(0, Math.min(1, confidence));
        String clarification = nullableText(json, "clarification");
        RouteDecision.SuggestedNextAction action = routeAction(text(json, "suggested_next_action"));
        return new RouteDecision(UUID.randomUUID(), kind, capability, secondaryIntent, operation, confidence, true,
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
        data.putIfAbsent("education", "学历待确认"); data.putIfAbsent("job_type", "全职");
        data.putIfAbsent("salary_range", "薪资待确认"); data.putIfAbsent("nice_to_haves", "加分项待确认");
        data.putIfAbsent("benefits", "福利待遇待确认"); data.putIfAbsent("warnings", List.of());
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

    // =====================================================================
    // AI 面试出题（DeepSeek 真实调用）。调用、鉴权或 Schema 异常直接上抛。
    // =====================================================================

    @Override
    public InterviewQuestionKit generateInterviewQuestions(GenerateInterviewQuestionsInput input) {
        requireEnabled();
        String systemPrompt = readPrompt(interviewQuestionPromptResource, "面试出题 Prompt 模板不可用");
        Map<String, Object> userBody = buildInterviewUserBody(input);
        String jsonText = completeJson(systemPrompt, json(userBody), 2800);
        return parseInterviewKit(readJson(jsonText), input);
    }

    /** 按 Prompt 约定的字段名构造 user input（使用下划线 JSON 字段名） */
    private Map<String, Object> buildInterviewUserBody(GenerateInterviewQuestionsInput input) {
        GenerateInterviewQuestionsInput.JobSnapshot job = input.job();
        GenerateInterviewQuestionsInput.CandidateSnapshot candidate = input.candidate();
        Map<String, Object> jobMap = new LinkedHashMap<>();
        jobMap.put("title", job == null ? "" : nullSafe(job.title()));
        jobMap.put("company_name", job == null ? "" : nullSafe(job.companyName()));
        jobMap.put("location", job == null ? "" : nullSafe(job.location()));
        jobMap.put("experience_level", job == null ? "" : nullSafe(job.experienceLevel()));
        jobMap.put("education", job == null ? "" : nullSafe(job.education()));
        jobMap.put("responsibilities", job == null ? "" : nullSafe(job.responsibilities()));
        jobMap.put("requirements", job == null ? "" : nullSafe(job.requirements()));
        jobMap.put("skills", job == null ? "" : nullSafe(job.skills()));
        Map<String, Object> candMap = new LinkedHashMap<>();
        candMap.put("name", candidate == null ? "" : nullSafe(candidate.name()));
        candMap.put("headline", candidate == null ? "" : nullSafe(candidate.headline()));
        candMap.put("skills", candidate == null || candidate.skills() == null ? List.of() : candidate.skills());
        candMap.put("summary", candidate == null ? "" : nullSafe(candidate.summary()));
        candMap.put("resume_text", candidate == null ? "" : nullSafe(candidate.resumeText()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", jobMap);
        body.put("candidate", candMap);
        body.put("requested_count", input.requestedCount());
        body.put("language_hint", "中文");
        return body;
    }

    /** 解析 DeepSeek 返回的 JSON → InterviewQuestionKit。字段校验失败时由业务层标记本次任务失败。 */
    private InterviewQuestionKit parseInterviewKit(JsonNode root, GenerateInterviewQuestionsInput input) {
        String matchSummary = text(root, "match_summary");
        if (matchSummary.isBlank()) throw contractInvalid("面试出题返回缺少 match_summary");

        JsonNode compsNode = root.path("core_competencies");
        if (!compsNode.isArray() || compsNode.size() == 0) throw contractInvalid("面试出题返回缺少 core_competencies");
        List<Competency> competencies = new ArrayList<>();
        for (JsonNode n : compsNode) {
            String name = text(n, "name");
            String desc = text(n, "description");
            if (!name.isBlank()) competencies.add(new Competency(name, desc.isBlank() ? "考察候选人在「" + name + "」上的落地深度与真实结果" : desc));
        }
        if (competencies.isEmpty()) throw contractInvalid("面试出题 core_competencies 无有效项");
        if (competencies.size() < 3) {
            // 补齐到 3 项（兜底）
            if (competencies.size() < 3) competencies.add(new Competency("岗位专业能力", "验证岗位相关的专业方法和业务理解"));
            if (competencies.size() < 3) competencies.add(new Competency("项目交付与问题解决", "验证问题拆解、协同推进和结果复盘能力"));
            if (competencies.size() < 3) competencies.add(new Competency("协作与沟通", "验证跨团队协作、冲突处理与汇报能力"));
        }

        JsonNode qNode = root.path("questions");
        if (!qNode.isArray() || qNode.size() == 0) throw contractInvalid("面试出题返回缺少 questions");
        int expected = Math.max(4, Math.min(input.requestedCount() <= 0 ? 8 : input.requestedCount(), 20));
        List<Question> questions = new ArrayList<>();
        List<String> competencyNames = competencies.stream().map(Competency::name).toList();
        for (JsonNode qn : qNode) {
            String category = normalizeCategory(text(qn, "category"), questions.size());
            String content = text(qn, "content");
            if (content.isBlank()) continue;
            String rawCore = text(qn, "core_competency");
            final String coreCompetency;
            if (!rawCore.isBlank() && competencyNames.stream().anyMatch(c -> c.equalsIgnoreCase(rawCore))) {
                coreCompetency = rawCore;
            } else {
                // 未提供或不匹配 → 轮询分配
                coreCompetency = competencyNames.get(questions.size() % competencyNames.size());
            }
            String rationale = text(qn, "rationale");
            if (rationale.isBlank()) rationale = coreCompetency + "：验证真实经验与交付结果";
            String focus = text(qn, "focus_points");
            if (focus.isBlank()) focus = "背景与本人角色；方法与决策依据；量化结果；风险识别与复盘；团队协作分工";
            String ref = text(qn, "reference_answer_points");
            if (ref.isBlank()) ref = "清晰陈述背景；给出有细节的方法；包含量化指标；说明反思与改进点";
            String scoring = text(qn, "scoring_points");
            if (scoring.isBlank()) scoring = "5分：证据充分、方法成熟且结果可验证；3分：经历真实、方法基本合理；1分：描述笼统或无法说明本人贡献";
            String refs = text(qn, "evidence_refs");
            if (refs.isBlank()) refs = "胜任力=" + coreCompetency;
            questions.add(new Question(category, content, rationale, focus, ref, scoring, refs, coreCompetency));
            if (questions.size() >= expected) break;
        }
        if (questions.isEmpty()) throw contractInvalid("面试出题 questions 无有效项");
        return new InterviewQuestionKit(matchSummary, competencies, questions);
    }

    /** 规范化 category：如果模型输出的不是 4 类之一，按题号轮询分配 */
    private static String normalizeCategory(String category, int questionIndex) {
        List<String> allowed = List.of("专业能力", "项目实践", "行为协作", "场景决策");
        if (category != null) {
            String t = category.trim();
            if (allowed.contains(t)) return t;
            // 子串匹配
            for (String a : allowed) if (t.contains(a)) return a;
        }
        return allowed.get(questionIndex % allowed.size());
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }

    private StructuredResult generateJd(String aiTaskId, StartAiTaskCommand command) {
        if (command.executionContext() == null) {
            throw contractInvalid("JD 生成请求缺少 execution_context");
        }
        String content = completeJson(jdSystemPrompt(), json(command.input()), JD_MAX_TOKENS);
        JsonNode node = readJson(content);
        Map<String, Object> data = objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
        normalizeJdFieldNames(data);
        applyJdDefaults(data);
        return new StructuredResult(command.executionContext().executionId(), aiTaskId, FlowCapability.JD_GENERATION,
                StructuredResult.Status.DRAFT_READY, "jd-v1", data, List.of(), List.of(),
                new StructuredResult.Provenance("deepseek-jd", "v1", "deepseek-jd-v1", model),
                new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    private StructuredResult generateJdStreaming(String aiTaskId, StartAiTaskCommand command, Consumer<String> onDelta) {
        if (command.executionContext() == null) throw contractInvalid("JD 生成请求缺少 execution_context");
        Map<String, Object> payload = Map.of("model", model,
                "messages", List.of(Map.of("role", "system", "content", jdSystemPrompt()),
                        Map.of("role", "user", "content", json(command.input()))),
                "response_format", Map.of("type", JSON_FORMAT),
                "thinking", Map.of("type", "disabled"),
                "stream", true, "max_tokens", JD_MAX_TOKENS);
        StringBuilder content = new StringBuilder();
        boolean outputTruncated = false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
            HttpResponse<java.io.InputStream> response = streamingClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw httpFailure(response.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    JsonNode event = readJson(data);
                    if ("length".equals(event.path("choices").path(0).path("finish_reason").asText())) {
                        outputTruncated = true;
                    }
                    String delta = event.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isBlank()) {
                        content.append(delta);
                        try { onDelta.accept(delta); } catch (RuntimeException ignored) { }
                    }
                }
            }
        } catch (ProviderFailure exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new ProviderFailure("DEEPSEEK_TIMEOUT", "DeepSeek 响应超时，请稍后重试");
        } catch (java.io.IOException exception) {
            throw new ProviderFailure("DEEPSEEK_NETWORK_ERROR", "无法连接 DeepSeek 服务，请检查网络后重试");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderFailure("DEEPSEEK_INTERRUPTED", "JD 生成被中断，请重试");
        } catch (Exception exception) {
            throw new ProviderFailure("DEEPSEEK_REQUEST_FAILED", "DeepSeek 请求失败，请稍后重试");
        }
        if (outputTruncated) {
            throw new ProviderFailure("DEEPSEEK_OUTPUT_TRUNCATED", "DeepSeek 输出被截断，请缩短招聘需求后重试");
        }
        try {
            JsonNode node = readJson(content.toString());
            // 某些模型会把符合约定的 JSON 对象再次编码成 JSON 字符串；安全解包一次后继续按契约校验。
            if (node.isTextual()) node = readJson(node.asText());
            if (!node.isObject()) {
                throw new ProviderFailure("DEEPSEEK_RESPONSE_ROOT_INVALID", "DeepSeek 返回格式不是 JD 所需的 JSON 对象，请重试");
            }
            Map<String, Object> data = objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
            normalizeJdFieldNames(data);
            applyJdDefaults(data);
            return new StructuredResult(command.executionContext().executionId(), aiTaskId, FlowCapability.JD_GENERATION,
                    StructuredResult.Status.DRAFT_READY, "jd-v1", data, List.of(), List.of(),
                    new StructuredResult.Provenance("deepseek-jd", "v1", "deepseek-jd-v1", model),
                    new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
        } catch (ProviderFailure exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProviderFailure("DEEPSEEK_RESPONSE_INVALID", "DeepSeek 返回内容格式异常，请重试");
        }
    }

    private StructuredResult generateCandidateScreening(String aiTaskId, StartAiTaskCommand command) {
        if (command.executionContext() == null) throw contractInvalid("简历筛选请求缺少 execution_context");
        JsonNode node = readJson(completeJson(readPrompt(candidateScreeningPromptResource, "简历筛选 Prompt 模板不可用"),
                json(command.input()), 1600));
        Map<String, Object> data = objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
        int score = score(data.get("score"));
        data.put("score", score);
        data.put("level", screeningLevel(textValue(data.get("level"), score)));
        data.put("matched_points", stringList(data.get("matched_points")));
        data.put("unmatched_points", stringList(data.get("unmatched_points")));
        data.put("negotiable_points", stringList(data.get("negotiable_points")));
        data.put("missing_information", stringList(data.get("missing_information")));
        data.put("risks", stringList(data.get("risks")));
        data.put("evidence", stringList(data.get("evidence")));
        return new StructuredResult(command.executionContext().executionId(), aiTaskId, FlowCapability.CANDIDATE_SCREENING,
                StructuredResult.Status.COMPLETED, "screening-v1", data, List.of(), List.of(),
                new StructuredResult.Provenance("deepseek-candidate-screening", "v1", "deepseek-screening-v1", model),
                new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    /**
     * 真实 LLM 简历解析。
     * 走 resume-parsing-v1.txt + DeepSeek /chat/completions response_format=json_object。
     * 成功后 data 保证包含 markdown / resume_count / job_linked / warnings 四字段。
     * 网络、超时、响应格式异常等临时性错误不再直接判失败：降级为「简历原始文本草稿」，
     * 保证用户仍有可编辑、可保存的结果；配置类错误（Key/数据策略/Prompt 缺失）才上抛由 outbox 重试退费。
     */
    private StructuredResult generateResumeParse(String aiTaskId, StartAiTaskCommand command) {
        if (command.executionContext() == null) throw contractInvalid("简历解析请求缺少 execution_context");
        Map<String, Object> input = command.input() instanceof Map<?, ?> map
                ? objectMapper.convertValue(map, new TypeReference<LinkedHashMap<String, Object>>() { })
                : new LinkedHashMap<>();
        try {
            String prompt = readPrompt(resumeParsingPromptResource, "简历解析 Prompt 模板不可用");
            JsonNode node = readJson(completeJson(prompt, json(command.input()), RESUME_PARSE_MAX_TOKENS));
            // 某些模型/代理会把约定 JSON 再次编码成字符串；安全解包一次后再按契约校验。
            if (node.isTextual()) node = readJson(node.asText());
            if (!node.isObject()) throw contractInvalid("DeepSeek 简历解析返回不是 JSON 对象");
            Map<String, Object> data = objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
            // 必须字段校验；markdown 为空说明模型未按契约产出。
            requireText(data, "markdown");
            // resume_count 缺失或非法时不再直接判失败，按输入 resumes 数量兜底。
            int resumeCount = normalizeResumeCount(data.get("resume_count"), resumeInputCount(input));
            data.put("resume_count", resumeCount);
            // job_linked 以输入为准，模型返回仅作参考。
            data.put("job_linked", jobInputPresent(input) || Boolean.TRUE.equals(data.get("job_linked")));
            // 兜底补充 warnings（用户要求 resumes 为空时必须有提示）
            List<String> warnings = new ArrayList<>(stringList(data.get("warnings")));
            if (resumeCount == 0 && warnings.stream().noneMatch("请先上传至少一份简历"::equals)) {
                warnings.add("请先上传至少一份简历");
            }
            if (!warnings.contains("解析结果仅供招聘人员参考，录用决策请结合人工复核")) {
                warnings.add("解析结果仅供招聘人员参考，录用决策请结合人工复核");
            }
            data.put("warnings", warnings);
            return new StructuredResult(command.executionContext().executionId(), aiTaskId, FlowCapability.RESUME_PARSING,
                    StructuredResult.Status.DRAFT_READY, "resume-parsing-v1", data, List.of(), List.of(),
                    new StructuredResult.Provenance("deepseek-resume-parsing", "v1", "deepseek-resume-parsing-v1", model),
                    new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
        } catch (RuntimeException exception) {
            // 配置类错误直接上抛（outbox 重试 3 次后标记失败并退费）；临时性错误降级为原始文本草稿。
            if (isConfigurationFailure(exception)) throw exception;
            log.warn("Resume parse DeepSeek call failed, fallback to local raw-text draft, taskId={}, cause={}: {}",
                    aiTaskId, exception.getClass().getSimpleName(), exception.getMessage());
            return buildLocalResumeParseResult(command, aiTaskId, input);
        }
    }

    /** resume_count 字段容错：数字直接用；可解析字符串转换；否则回退到输入简历数量。 */
    private static int normalizeResumeCount(Object value, int fallback) {
        if (value instanceof Number number) return Math.max(0, number.intValue());
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resumeInputList(Map<String, Object> input) {
        Object resumes = input.get("resumes");
        if (!(resumes instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static int resumeInputCount(Map<String, Object> input) {
        return resumeInputList(input).size();
    }

    private static boolean jobInputPresent(Map<String, Object> input) {
        return input.get("job") instanceof Map<?, ?> map && !map.isEmpty();
    }

    /**
     * DeepSeek 临时不可用（网络/超时/响应格式异常）时的本地降级：
     * 把已从文件提取出的简历原始文本组装成 markdown 草稿写入解析结果，
     * 用户仍可查看、编辑、保存，服务恢复后点「AI 解析」即可重新生成结构化版本。
     */
    private StructuredResult buildLocalResumeParseResult(StartAiTaskCommand command, String aiTaskId, Map<String, Object> input) {
        List<Map<String, Object>> resumes = resumeInputList(input);
        StringBuilder markdown = new StringBuilder(4_096);
        if (resumes.isEmpty()) {
            markdown.append("当前暂未上传简历文件。点击左侧「上传简历」补充 PDF / DOCX / TXT，AI 将自动重新解析并生成结构化结果。\n");
        } else {
            markdown.append("> ⚠️ AI 解析服务暂时不可用，以下为简历文件提取的**原始文本**，可先人工查看、编辑并保存；")
                    .append("服务恢复后点击「AI 解析」可重新生成结构化版本。\n\n");
            int index = 1;
            for (Map<String, Object> resume : resumes) {
                String filename = String.valueOf(resume.getOrDefault("filename", "简历 " + index));
                String text = resume.get("text") == null ? "" : String.valueOf(resume.get("text"));
                markdown.append("## 简历 ").append(index).append("：").append(filename).append("\n\n");
                markdown.append("### 1. 基本信息\n- 姓名：待 AI 解析或人工补充\n- 文档大小估算：约 ").append(text.length()).append(" 字符\n\n");
                markdown.append("### 2. 原始简历文本（待人工 / AI 整理）\n");
                String trimmed = text.strip();
                if (trimmed.isEmpty()) {
                    markdown.append("未提取到文本，可能是扫描件或图片简历，建议人工打开原文件查看。\n\n");
                } else {
                    // 单份简历原文最多保留 8000 字符，避免草稿超长
                    String bounded = trimmed.length() > 8_000
                            ? trimmed.substring(0, 8_000) + "\n……（原文过长已截断，可打开原文件查看完整内容）"
                            : trimmed;
                    markdown.append("```text\n").append(bounded).append("\n```\n\n");
                }
                index++;
            }
            markdown.append("### 后续建议\n1. AI 服务恢复后点击「AI 解析」生成结构化版本；\n2. 也可直接在本文本框内人工整理后保存。\n");
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("AI 解析服务暂时不可用，当前为简历原始文本的降级展示，请人工复核整理");
        if (resumes.isEmpty()) warnings.add("请先上传至少一份简历");
        warnings.add("解析结果仅供招聘人员参考，录用决策请结合人工复核");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("markdown", markdown.toString());
        data.put("resume_count", resumes.size());
        data.put("job_linked", jobInputPresent(input));
        data.put("warnings", warnings);
        return new StructuredResult(command.executionContext().executionId(), aiTaskId, FlowCapability.RESUME_PARSING,
                StructuredResult.Status.DRAFT_READY, "resume-parsing-v1", data, List.of(), List.of(),
                new StructuredResult.Provenance("local-resume-parsing", "v1", "local-resume-parsing-fallback-v1", model),
                new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
    }

    /** 配置类错误（API Key / 数据策略 / Prompt 缺失）不应降级，必须上抛暴露配置问题。 */
    private static boolean isConfigurationFailure(RuntimeException exception) {
        if (exception instanceof ApiException apiException) {
            return switch (apiException.code()) {
                case "AI_AUTH_FAILED", "DATA_POLICY_BLOCKED", "AI_PROMPT_UNAVAILABLE" -> true;
                default -> false;
            };
        }
        if (exception instanceof ProviderFailure failure) {
            return switch (failure.code()) {
                case "DEEPSEEK_AUTH_CONFIGURATION", "DEEPSEEK_DATA_POLICY", "DEEPSEEK_PROMPT_UNAVAILABLE" -> true;
                default -> false;
            };
        }
        return false;
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
            JsonNode firstChoice = root.path("choices").path(0);
            String finishReason = firstChoice.path("finish_reason").asText("");
            String content = firstChoice.path("message").path("content").asText("");
            if (content.isBlank()) throw contractInvalid("DeepSeek 返回了空的 JSON 内容");
            // 输出被 max_tokens 截断时内容必然是不完整 JSON，提前给出明确错误，
            // 避免下游误报「不是有效 JSON」（也便于调用方决定是否降级）。
            if ("length".equalsIgnoreCase(finishReason)) {
                throw new ProviderFailure("DEEPSEEK_OUTPUT_TRUNCATED", "DeepSeek 输出长度超限被截断，请减少输入篇幅后重试");
            }
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

    private static ProviderFailure httpFailure(int statusCode) {
        String message = switch (statusCode) {
            case 400 -> "DeepSeek 拒绝了本次请求，请检查模型配置后重试";
            case 401, 403 -> "DeepSeek 鉴权失败，请检查 API Key 和服务授权后重试";
            case 402 -> "DeepSeek 账户余额不足，请充值后重试";
            case 408, 504 -> "DeepSeek 响应超时，请稍后重试";
            case 429 -> "DeepSeek 请求过于频繁，请稍后重试";
            default -> statusCode >= 500
                    ? "DeepSeek 服务暂不可用，请稍后重试"
                    : "DeepSeek 请求失败，请稍后重试";
        };
        return new ProviderFailure("DEEPSEEK_HTTP_" + statusCode, message);
    }

    private static void normalizeJdFieldNames(Map<String, Object> data) {
        copyFirstPresent(data, "title", "job_title", "position_title");
        copyFirstPresent(data, "company_name", "companyName", "company");
        copyFirstPresent(data, "experience_level", "experienceLevel");
        copyFirstPresent(data, "job_type", "jobType");
        copyFirstPresent(data, "salary_range", "salaryRange");
        copyFirstPresent(data, "nice_to_haves", "niceToHaves");
        copyFirstPresent(data, "talent_profile", "talentProfile");
    }

    private static void copyFirstPresent(Map<String, Object> data, String target, String... aliases) {
        if (!textValue(data.get(target)).isBlank()) return;
        for (String alias : aliases) {
            if (!textValue(data.get(alias)).isBlank()) {
                data.put(target, data.get(alias));
                return;
            }
        }
    }

    private static void applyJdDefaults(Map<String, Object> data) {
        defaultText(data, "title", "职位名称待确认");
        defaultText(data, "company_name", "企业待确认");
        defaultText(data, "location", "工作地点待确认");
        defaultText(data, "experience_level", "经验待确认");
        defaultText(data, "education", "学历待确认");
        defaultText(data, "job_type", "全职");
        defaultText(data, "salary_range", "薪资待确认");
        defaultText(data, "responsibilities", "岗位职责待确认");
        defaultText(data, "requirements", "任职要求待确认");
        defaultText(data, "skills", "关键技能待确认");
        defaultText(data, "nice_to_haves", "加分项待确认");
        defaultText(data, "benefits", "福利待遇待确认");
        defaultText(data, "talent_profile", "人才画像待确认");
        data.putIfAbsent("warnings", List.of());
    }

    private static void defaultText(Map<String, Object> data, String field, String fallback) {
        if (textValue(data.get(field)).isBlank()) data.put(field, fallback);
    }

    private static String textValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static ProviderFailure safeFailure(RuntimeException exception) {
        if (exception instanceof ProviderFailure failure) return failure;
        if (exception instanceof ApiException apiException) {
            return switch (apiException.code()) {
                case "AI_CONTRACT_INVALID" -> new ProviderFailure("DEEPSEEK_RESPONSE_INVALID", "DeepSeek 返回内容格式异常，请重试");
                case "AI_AUTH_FAILED" -> new ProviderFailure("DEEPSEEK_AUTH_CONFIGURATION", "DeepSeek API Key 未配置或无效，请更新后重试");
                case "DATA_POLICY_BLOCKED" -> new ProviderFailure("DEEPSEEK_DATA_POLICY", "未获得 DeepSeek 外部数据处理授权，请联系管理员");
                case "AI_PROMPT_UNAVAILABLE" -> new ProviderFailure("DEEPSEEK_PROMPT_UNAVAILABLE", "JD 生成模板不可用，请联系管理员");
                default -> new ProviderFailure("DEEPSEEK_REQUEST_FAILED", "DeepSeek 请求失败，请稍后重试");
            };
        }
        return new ProviderFailure("DEEPSEEK_INTERNAL_ERROR", "JD 生成发生内部错误，请重试");
    }

    private static final class ProviderFailure extends RuntimeException {
        private final String code;
        private final String safeMessage;

        private ProviderFailure(String code, String safeMessage) {
            super(safeMessage);
            this.code = code;
            this.safeMessage = safeMessage;
        }

        private String code() { return code; }
        private String safeMessage() { return safeMessage; }
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
        try { return objectMapper.readTree(stripCodeFence(value)); }
        catch (JsonProcessingException exception) { throw contractInvalid("DeepSeek 返回的内容不是有效 JSON"); }
    }

    /**
     * 部分模型 / 代理即使在 response_format=json_object 下，仍会用 ```json ... ``` 代码块包裹内容，
     * 统一剥离首尾围栏后再解析，避免误判为「不是有效 JSON」。
     */
    private static String stripCodeFence(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) text = text.substring(firstNewline + 1);
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) text = text.substring(0, lastFence);
        }
        return text.trim();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw contractInvalid("无法构造 DeepSeek 请求"); }
    }

    private static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : ""; }
    private static String textValue(Object value, int score) { return value == null ? screeningLevel(score) : String.valueOf(value).trim(); }
    private static int score(Object value) {
        try {
            int score = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            if (score < 0 || score > 100) throw contractInvalid("DeepSeek 简历筛选分数必须在 0 至 100 之间");
            return score;
        } catch (NumberFormatException exception) { throw contractInvalid("DeepSeek 简历筛选缺少有效分数"); }
    }
    private static String screeningLevel(String value) {
        return List.of("STRONG_MATCH", "MATCH", "GENERAL_MATCH", "WEAK_MATCH").contains(value) ? value : "WEAK_MATCH";
    }
    private static String screeningLevel(int score) {
        return score >= 85 ? "STRONG_MATCH" : score >= 70 ? "MATCH" : score >= 60 ? "GENERAL_MATCH" : "WEAK_MATCH";
    }
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().filter(String.class::isInstance).map(String.class::cast).filter(item -> !item.isBlank()).limit(20).toList();
    }
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
    private static RouteDecision.Operation routeOperation(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;
        try { return RouteDecision.Operation.fromValue(value); }
        catch (IllegalArgumentException exception) { throw contractInvalid("DeepSeek 返回了无效的动作语义"); }
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
    private static ApiException unsupported(AiCapability capability) { return new ApiException("AI_CAPABILITY_UNAVAILABLE", "DeepSeek 临时适配器不支持该能力：" + capability, HttpStatus.NOT_IMPLEMENTED); }
}
