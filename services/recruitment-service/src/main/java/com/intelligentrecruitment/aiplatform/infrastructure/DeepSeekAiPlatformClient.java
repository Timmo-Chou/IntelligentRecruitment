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
                0, 1, 0, Instant.now());
        tasks.put(aiTaskId, task);
        idempotencyIndex.put(command.idempotencyKey(), aiTaskId);
        CompletableFuture.runAsync(() -> {
            try {
                StructuredResult result;
                if (command.capability() == AiCapability.JD_GENERATION) {
                    result = generateJdStreaming(aiTaskId, command, onDelta);
                } else if (command.capability() == AiCapability.RESUME_PARSING) {
                    // 真实 LLM 简历解析：走 resume-parsing-v1 Prompt + DeepSeek /chat/completions；
                    // 任何异常（鉴权、超时、API 异常、JSON 非法、字段缺失）一律降级到 Mock 侧结构，保证业务可用。
                    try {
                        result = generateResumeParse(aiTaskId, command);
                    } catch (RuntimeException exception) {
                        result = new MockAiPlatformClient().mockResumeStructuredResult(aiTaskId, command);
                    }
                } else {
                    result = generateCandidateScreening(aiTaskId, command);
                }
                results.put(aiTaskId, result);
                tasks.put(aiTaskId, new AiTask(aiTaskId, command.businessTaskId(), command.capability(), AiTaskStatus.COMPLETED,
                        1, 1, 100, task.acceptedAt()));
            } catch (RuntimeException exception) {
                tasks.put(aiTaskId, new AiTask(aiTaskId, command.businessTaskId(), command.capability(), AiTaskStatus.FAILED,
                        0, 1, 100, task.acceptedAt()));
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
                AiTaskStatus.CANCELLED, current.completed(), current.total(), current.percent(), current.acceptedAt());
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
    // AI 面试出题（DeepSeek 真实调用）
    // 任何异常（requireEnabled 不通过、鉴权失败、超时、JSON 非法、字段缺失）
    // 一律降级到 MockAiPlatformClient，保证业务不崩。
    // =====================================================================

    @Override
    public InterviewQuestionKit generateInterviewQuestions(GenerateInterviewQuestionsInput input) {
        try {
            requireEnabled();
            String systemPrompt = readPrompt(interviewQuestionPromptResource, "面试出题 Prompt 模板不可用");
            // 构造 user JSON：与 interview-question-v1.txt 约定的输入字段对齐（下划线命名）
            Map<String, Object> userBody = buildInterviewUserBody(input);
            String jsonText = completeJson(systemPrompt, json(userBody), 2800);
            JsonNode root = readJson(jsonText);
            return parseInterviewKit(root, input);
        } catch (RuntimeException exception) {
            // 所有异常降级 Mock（含 DATA_POLICY_BLOCKED / AI_AUTH_FAILED / JSON 解析错 / 字段缺 等）
            return new MockAiPlatformClient().mockInterviewQuestionKit(input);
        }
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

    /** 解析 DeepSeek 返回的 JSON → InterviewQuestionKit。字段校验失败抛异常 → 上层降级 Mock */
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
        data.putIfAbsent("salary_range", "薪资待确认");
        data.putIfAbsent("nice_to_haves", "加分项待确认");
        data.putIfAbsent("benefits", "福利待遇待确认");
        data.putIfAbsent("warnings", List.of());
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
                "response_format", Map.of("type", JSON_FORMAT), "stream", true, "max_tokens", 1800);
        StringBuilder content = new StringBuilder();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8)).build();
            HttpResponse<java.io.InputStream> response = streamingClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("AI provider returned " + response.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    JsonNode event = readJson(data);
                    String delta = event.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isBlank()) {
                        content.append(delta);
                        try { onDelta.accept(delta); } catch (RuntimeException ignored) { }
                    }
                }
            }
        } catch (Exception exception) {
            throw new ApiException("AI_PROVIDER_UNAVAILABLE", "DeepSeek 流式生成失败", HttpStatus.SERVICE_UNAVAILABLE);
        }
        JsonNode node = readJson(content.toString());
        Map<String, Object> data = objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
        requireText(data, "title"); requireText(data, "company_name"); requireText(data, "responsibilities");
        requireText(data, "requirements"); requireText(data, "skills"); requireText(data, "talent_profile");
        data.putIfAbsent("location", "工作地点待确认"); data.putIfAbsent("experience_level", "经验待确认");
        data.putIfAbsent("education", "学历待确认"); data.putIfAbsent("job_type", "全职"); data.putIfAbsent("salary_range", "薪资待确认");
        data.putIfAbsent("nice_to_haves", "加分项待确认"); data.putIfAbsent("benefits", "福利待遇待确认"); data.putIfAbsent("warnings", List.of());
        return new StructuredResult(command.executionContext().executionId(), aiTaskId, FlowCapability.JD_GENERATION,
                StructuredResult.Status.DRAFT_READY, "jd-v1", data, List.of(), List.of(),
                new StructuredResult.Provenance("deepseek-jd", "v1", "deepseek-jd-v1", model),
                new StructuredResult.Usage(0, 0, 0, "CNY"), Instant.now());
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
     * 成功后 data 保证包含 markdown / resume_count / job_linked / warnings 四字段；
     * 任何字段缺失或格式异常抛 RuntimeException，交由 startTask 外层兜底降级 Mock。
     */
    private StructuredResult generateResumeParse(String aiTaskId, StartAiTaskCommand command) {
        if (command.executionContext() == null) throw contractInvalid("简历解析请求缺少 execution_context");
        String prompt = readPrompt(resumeParsingPromptResource, "简历解析 Prompt 模板不可用");
        JsonNode node = readJson(completeJson(prompt, json(command.input()), 2400));
        Map<String, Object> data = objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() { });
        // 必须字段校验，失败抛异常给上层降级
        requireText(data, "markdown");
        if (!data.containsKey("resume_count")) throw contractInvalid("DeepSeek 简历解析缺少 resume_count");
        Object resumeCountValue = data.get("resume_count");
        int resumeCount = resumeCountValue instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(resumeCountValue));
        data.put("resume_count", resumeCount);
        data.put("job_linked", Boolean.TRUE.equals(data.get("job_linked")));
        data.put("warnings", stringList(data.get("warnings")));
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
