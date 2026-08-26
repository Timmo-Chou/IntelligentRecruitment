package com.intelligentrecruitment.recruitment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.billing.application.BillingService;
import com.intelligentrecruitment.jobs.application.JobService;
import com.intelligentrecruitment.recruitment.application.JdDraftGenerator.GenerationInput;
import com.intelligentrecruitment.recruitment.application.JdDraftGenerator.JdDraftContent;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService.WorkspaceScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class RecruitmentService {

    private static final String JD_PRICING_VERSION = "JD_MOCK_V1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessService workspaceAccess;
    private final BillingService billing;
    private final AiPlatformClient aiPlatform;
    private final JdDraftGenerator draftGenerator;
    private final JobService jobs;
    private final long jdPriceMinor;

    public RecruitmentService(JdbcTemplate jdbc, ObjectMapper objectMapper, WorkspaceAccessService workspaceAccess,
                              BillingService billing, AiPlatformClient aiPlatform, JdDraftGenerator draftGenerator,
                              JobService jobs,
                              @Value("${app.phase3.jd-generation-price-minor:80}") long jdPriceMinor) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workspaceAccess = workspaceAccess;
        this.billing = billing;
        this.aiPlatform = aiPlatform;
        this.draftGenerator = draftGenerator;
        this.jobs = jobs;
        this.jdPriceMinor = jdPriceMinor;
    }

    @Transactional
    public TaskDetail createTask(UUID userId, UUID workspaceId, String idempotencyKey, CreateTaskInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        String key = requiredIdempotencyKey(idempotencyKey);
        if (input == null) throw validation("招聘任务不能为空");
        String title = required(input.title(), "招聘任务名称不能为空", 200);
        String requirement = required(input.initialRequirement(), "请描述招聘需求", 20_000);
        String requestHash = SecurityHashes.sha256(title + "\n" + requirement);
        List<ExistingReference> existing = jdbc.query("""
                SELECT id,request_hash FROM recruitment_tasks WHERE workspace_id=? AND idempotency_key=?
                """, (rs, n) -> new ExistingReference(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                workspaceId, key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().requestHash().equals(requestHash)) throw idempotencyConflict();
            return detailScoped(workspaceId, existing.getFirst().id());
        }
        UUID taskId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO recruitment_tasks
                (id,company_id,workspace_id,title,initial_requirement,status,current_stage,idempotency_key,
                 request_hash,created_by,created_at,updated_at)
                VALUES (?,?,?, ?,?,'ACTIVE','COLLECTING_REQUIREMENTS',?,?, ?,?,?)
                """, taskId, scope.companyId(), workspaceId, title, requirement, key, requestHash, userId,
                timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO conversations
                (id,company_id,workspace_id,recruitment_task_id,status,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',?,?)
                """, conversationId, scope.companyId(), workspaceId, taskId, timestamp(now), timestamp(now));
        insertMessage(scope, conversationId, "USER", requirement, "REQUIREMENT_CHAT", userId, now);
        audit(userId, scope, "RECRUITMENT_TASK_CREATED", "RECRUITMENT_TASK", taskId);
        return detailScoped(workspaceId, taskId);
    }

    public List<TaskSummary> listTasks(UUID userId, UUID workspaceId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return jdbc.query("""
                SELECT t.id,t.company_id,t.workspace_id,t.title,t.status,t.current_stage,t.created_by,
                       t.created_at,t.updated_at,j.id AS job_id,j.title AS job_title
                FROM recruitment_tasks t
                LEFT JOIN jobs j ON j.recruitment_task_id=t.id AND j.workspace_id=t.workspace_id AND j.status<>'ARCHIVED'
                WHERE t.workspace_id=? ORDER BY t.updated_at DESC LIMIT 100
                """, (rs, n) -> new TaskSummary(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("title"), rs.getString("status"), rs.getString("current_stage"),
                rs.getObject("job_id", UUID.class), rs.getString("job_title"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), workspaceId);
    }

    public TaskDetail getTask(UUID userId, UUID workspaceId, UUID taskId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return detailScoped(workspaceId, taskId);
    }

    @Transactional
    public TaskDetail addMessage(UUID userId, UUID workspaceId, UUID taskId, MessageInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        String content = required(input == null ? null : input.content(), "消息不能为空", 20_000);
        insertMessage(scope, task.conversationId(), "USER", content, "REQUIREMENT_CHAT", userId, Instant.now());
        jdbc.update("""
                UPDATE recruitment_tasks SET current_stage='COLLECTING_REQUIREMENTS',updated_at=?
                WHERE id=? AND workspace_id=?
                """, timestamp(Instant.now()), taskId, workspaceId);
        return detailScoped(workspaceId, taskId);
    }

    @Transactional
    public TaskDetail generateJd(UUID userId, UUID workspaceId, UUID taskId, String idempotencyKey,
                                 GenerateJdInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        String key = requiredIdempotencyKey(idempotencyKey);
        String payloadHash = hash(input);
        List<ExistingReference> existing = jdbc.query("""
                SELECT id,input_hash AS request_hash FROM ai_runs WHERE workspace_id=? AND idempotency_key=?
                """, (rs, n) -> new ExistingReference(rs.getObject("id", UUID.class), rs.getString("request_hash")),
                workspaceId, key);
        if (!existing.isEmpty()) {
            if (!existing.getFirst().requestHash().equals(payloadHash)) throw idempotencyConflict();
            return detailScoped(workspaceId, taskId);
        }
        String requirement = optional(input == null ? null : input.requirement(), 20_000);
        if (requirement.isBlank()) requirement = task.initialRequirement();
        String scenario = normalizedScenario(input == null ? null : input.scenario());
        UUID runId = UUID.randomUUID();
        int attempt = nextAttempt(taskId);
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO ai_runs
                (id,company_id,workspace_id,recruitment_task_id,capability,status,progress,attempt_number,
                 idempotency_key,input_hash,pricing_version,estimated_amount_minor,created_by,created_at)
                VALUES (?,?,?,?, 'JD_GENERATION','RUNNING',10,?,?,?,?,?,?,?)
                """, runId, scope.companyId(), workspaceId, taskId, attempt, key, payloadHash,
                JD_PRICING_VERSION, jdPriceMinor, userId, timestamp(now));
        String billingReference = "jd-run:" + runId;
        billing.reserve(userId, workspaceId, billingReference, jdPriceMinor);
        Map<String, Object> aiInput = new LinkedHashMap<>();
        aiInput.put("requirement", requirement);
        aiInput.put("scenario", scenario);
        AiTask aiTask = aiPlatform.startTask(new StartAiTaskCommand(workspaceId.toString(),
                scope.companyId() == null ? null : scope.companyId().toString(), userId.toString(), taskId.toString(),
                key, AiCapability.JD_GENERATION, aiInput));
        jdbc.update("UPDATE ai_runs SET provider_task_id=?,progress=60 WHERE id=?", aiTask.aiTaskId(), runId);

        if (!"NORMAL".equals(scenario)) {
            String code = "TIMEOUT".equals(scenario) ? "AI_TIMEOUT" : "AI_SCHEMA_INVALID";
            String message = "TIMEOUT".equals(scenario) ? "AI 生成超时，请重试" : "AI 返回结构不合法，请重试";
            jdbc.update("""
                    UPDATE ai_runs SET status='FAILED',progress=100,error_code=?,error_message=?,completed_at=? WHERE id=?
                    """, code, message, timestamp(Instant.now()), runId);
            billing.settle(userId, workspaceId, billingReference, 0);
            insertMessage(scope, task.conversationId(), "ASSISTANT", message, "JD_GENERATION", null, Instant.now());
            jdbc.update("UPDATE recruitment_tasks SET current_stage='JD_GENERATION_FAILED',updated_at=? WHERE id=?",
                    timestamp(Instant.now()), taskId);
            audit(userId, scope, "JD_GENERATION_FAILED", "AI_RUN", runId);
            return detailScoped(workspaceId, taskId);
        }

        GenerationInput generationInput = new GenerationInput(requirement, value(input, GenerateJdInput::title),
                value(input, GenerateJdInput::companyName), value(input, GenerateJdInput::location),
                value(input, GenerateJdInput::experienceLevel), value(input, GenerateJdInput::education),
                value(input, GenerateJdInput::jobType), value(input, GenerateJdInput::skills));
        JdDraftContent draft = draftGenerator.generate(generationInput);
        upsertDraft(scope, taskId, runId, userId, draft);
        Instant completed = Instant.now();
        jdbc.update("""
                UPDATE ai_runs SET status='COMPLETED',progress=100,settled_amount_minor=?,completed_at=? WHERE id=?
                """, jdPriceMinor, timestamp(completed), runId);
        billing.settle(userId, workspaceId, billingReference, jdPriceMinor);
        insertMessage(scope, task.conversationId(), "ASSISTANT",
                "JD 草稿已生成。请检查职责、任职要求和待确认项，确认后再进入职位库。",
                "JD_GENERATION", null, completed);
        jdbc.update("""
                UPDATE recruitment_tasks SET current_stage='AWAITING_JD_CONFIRMATION',updated_at=? WHERE id=?
                """, timestamp(completed), taskId);
        audit(userId, scope, "JD_DRAFT_GENERATED", "AI_RUN", runId);
        return detailScoped(workspaceId, taskId);
    }

    @Transactional
    public TaskDetail updateDraft(UUID userId, UUID workspaceId, UUID taskId, UpdateDraftInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        if (input == null) throw validation("JD 草稿不能为空");
        String warnings = json(input.warnings() == null ? List.of() : input.warnings());
        int updated = jdbc.update("""
                UPDATE jd_drafts SET revision=revision+1,title=?,company_name=?,location=?,experience_level=?,
                    education=?,job_type=?,responsibilities=?,requirements=?,skills=?,talent_profile=?,warnings=?::jsonb,
                    updated_by=?,updated_at=?
                WHERE recruitment_task_id=? AND workspace_id=? AND revision=? AND status='DRAFT'
                """, required(input.title(), "职位名称不能为空", 200),
                required(input.companyName(), "企业名称不能为空", 200), optional(input.location(), 200),
                optional(input.experienceLevel(), 80), optional(input.education(), 80),
                defaulted(input.jobType(), "全职", 50), optional(input.responsibilities(), 20_000),
                optional(input.requirements(), 20_000), optional(input.skills(), 4_000),
                optional(input.talentProfile(), 10_000), warnings, userId, timestamp(Instant.now()), taskId,
                workspaceId, input.revision());
        if (updated == 0) {
            throw new ApiException("JD_DRAFT_VERSION_CONFLICT", "JD 草稿已更新或已确认，请刷新后重试", HttpStatus.CONFLICT);
        }
        insertMessage(scope, task.conversationId(), "SYSTEM", "JD 草稿已由招聘人员编辑保存。",
                "JD_GENERATION", userId, Instant.now());
        audit(userId, scope, "JD_DRAFT_UPDATED", "JD_DRAFT", taskId);
        return detailScoped(workspaceId, taskId);
    }

    @Transactional
    public JobService.JobView confirmDraft(UUID userId, UUID workspaceId, UUID taskId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        TaskRow task = taskForUpdate(workspaceId, taskId);
        List<JdDraftView> drafts = draftRows(workspaceId, taskId);
        if (drafts.isEmpty()) throw new ApiException("JD_DRAFT_NOT_FOUND", "请先生成 JD 草稿", HttpStatus.CONFLICT);
        JdDraftView draft = drafts.getFirst();
        if ("CONFIRMED".equals(draft.status())) {
            List<UUID> jobIds = jdbc.query("SELECT id FROM jobs WHERE recruitment_task_id=? AND workspace_id=?",
                    (rs, n) -> rs.getObject("id", UUID.class), taskId, workspaceId);
            if (!jobIds.isEmpty()) return jobs.get(userId, workspaceId, jobIds.getFirst());
        }
        UUID sourceAiRunId = jdbc.queryForObject("SELECT source_ai_run_id FROM jd_drafts WHERE id=?",
                UUID.class, draft.id());
        JobService.JobInput jobInput = new JobService.JobInput(draft.title(), draft.companyName(), draft.location(),
                draft.responsibilities(), draft.requirements(), draft.skills(), draft.experienceLevel(),
                draft.education(), draft.jobType());
        JobService.JobView job = jobs.createFromConfirmedJd(userId, workspaceId, taskId, sourceAiRunId, jobInput,
                draft.talentProfile(), json(draft.warnings()));
        Instant now = Instant.now();
        jdbc.update("UPDATE jd_drafts SET status='CONFIRMED',updated_by=?,updated_at=? WHERE id=?",
                userId, timestamp(now), draft.id());
        jdbc.update("""
                UPDATE recruitment_tasks SET status='COMPLETED',current_stage='JD_CONFIRMED',updated_at=? WHERE id=?
                """, timestamp(now), taskId);
        insertMessage(scope, task.conversationId(), "ASSISTANT",
                "JD 已确认并进入职位库，后续修改将创建新的职位版本。", "JD_GENERATION", null, now);
        audit(userId, scope, "JD_CONFIRMED", "RECRUITMENT_TASK", taskId);
        return job;
    }

    private TaskDetail detailScoped(UUID workspaceId, UUID taskId) {
        List<TaskSummary> summaries = jdbc.query("""
                SELECT t.id,t.company_id,t.workspace_id,t.title,t.status,t.current_stage,t.created_by,
                       t.created_at,t.updated_at,j.id AS job_id,j.title AS job_title
                FROM recruitment_tasks t
                LEFT JOIN jobs j ON j.recruitment_task_id=t.id AND j.workspace_id=t.workspace_id AND j.status<>'ARCHIVED'
                WHERE t.id=? AND t.workspace_id=?
                """, (rs, n) -> new TaskSummary(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("title"), rs.getString("status"), rs.getString("current_stage"),
                rs.getObject("job_id", UUID.class), rs.getString("job_title"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), taskId, workspaceId);
        if (summaries.isEmpty()) throw taskNotFound();
        UUID conversationId = jdbc.queryForObject("""
                SELECT id FROM conversations WHERE recruitment_task_id=? AND workspace_id=?
                """, UUID.class, taskId, workspaceId);
        List<MessageView> messages = jdbc.query("""
                SELECT id,role,content,capability,sequence_number,created_by,created_at
                FROM messages WHERE conversation_id=? AND workspace_id=? ORDER BY sequence_number
                """, (rs, n) -> new MessageView(rs.getObject("id", UUID.class), rs.getString("role"),
                rs.getString("content"), rs.getString("capability"), rs.getInt("sequence_number"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant()),
                conversationId, workspaceId);
        JdDraftView draft = draftRows(workspaceId, taskId).stream().findFirst().orElse(null);
        List<AiRunView> runs = jdbc.query("""
                SELECT id,provider_task_id,status,progress,attempt_number,pricing_version,estimated_amount_minor,
                       settled_amount_minor,error_code,error_message,created_at,completed_at
                FROM ai_runs WHERE recruitment_task_id=? AND workspace_id=? ORDER BY created_at DESC LIMIT 1
                """, (rs, n) -> new AiRunView(rs.getObject("id", UUID.class), rs.getString("provider_task_id"),
                rs.getString("status"), rs.getInt("progress"), rs.getInt("attempt_number"),
                rs.getString("pricing_version"), rs.getLong("estimated_amount_minor"),
                rs.getLong("settled_amount_minor"), rs.getString("error_code"), rs.getString("error_message"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant()),
                taskId, workspaceId);
        return new TaskDetail(summaries.getFirst(), conversationId, messages, draft,
                runs.isEmpty() ? null : runs.getFirst());
    }

    private TaskRow taskForUpdate(UUID workspaceId, UUID taskId) {
        List<TaskRow> rows = jdbc.query("""
                SELECT t.id,t.initial_requirement,c.id AS conversation_id
                FROM recruitment_tasks t JOIN conversations c ON c.recruitment_task_id=t.id
                WHERE t.id=? AND t.workspace_id=? FOR UPDATE OF t
                """, (rs, n) -> new TaskRow(rs.getObject("id", UUID.class), rs.getString("initial_requirement"),
                rs.getObject("conversation_id", UUID.class)), taskId, workspaceId);
        if (rows.isEmpty()) throw taskNotFound();
        return rows.getFirst();
    }

    private List<JdDraftView> draftRows(UUID workspaceId, UUID taskId) {
        return jdbc.query("""
                SELECT id,revision,title,company_name,location,experience_level,education,job_type,
                       responsibilities,requirements,skills,talent_profile,warnings::text,status,updated_at
                FROM jd_drafts WHERE recruitment_task_id=? AND workspace_id=?
                """, (rs, n) -> new JdDraftView(rs.getObject("id", UUID.class), rs.getInt("revision"),
                rs.getString("title"), rs.getString("company_name"), rs.getString("location"),
                rs.getString("experience_level"), rs.getString("education"), rs.getString("job_type"),
                rs.getString("responsibilities"), rs.getString("requirements"), rs.getString("skills"),
                rs.getString("talent_profile"), parseWarnings(rs.getString("warnings")), rs.getString("status"),
                rs.getTimestamp("updated_at").toInstant()), taskId, workspaceId);
    }

    private void upsertDraft(WorkspaceScope scope, UUID taskId, UUID runId, UUID userId, JdDraftContent draft) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO jd_drafts
                (id,company_id,workspace_id,recruitment_task_id,source_ai_run_id,revision,title,company_name,
                 location,experience_level,education,job_type,responsibilities,requirements,skills,talent_profile,
                 warnings,status,updated_by,created_at,updated_at)
                VALUES (?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?::jsonb,'DRAFT',?,?,?)
                ON CONFLICT (recruitment_task_id) DO UPDATE SET source_ai_run_id=EXCLUDED.source_ai_run_id,
                 revision=jd_drafts.revision+1,title=EXCLUDED.title,company_name=EXCLUDED.company_name,
                 location=EXCLUDED.location,experience_level=EXCLUDED.experience_level,education=EXCLUDED.education,
                 job_type=EXCLUDED.job_type,responsibilities=EXCLUDED.responsibilities,
                 requirements=EXCLUDED.requirements,skills=EXCLUDED.skills,talent_profile=EXCLUDED.talent_profile,
                 warnings=EXCLUDED.warnings,status='DRAFT',updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at
                """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), taskId, runId, draft.title(),
                draft.companyName(), draft.location(), draft.experienceLevel(), draft.education(), draft.jobType(),
                draft.responsibilities(), draft.requirements(), draft.skills(), draft.talentProfile(),
                json(draft.warnings()), userId, timestamp(now), timestamp(now));
    }

    private void insertMessage(WorkspaceScope scope, UUID conversationId, String role, String content,
                               String capability, UUID createdBy, Instant now) {
        jdbc.queryForObject("SELECT id FROM conversations WHERE id=? FOR UPDATE", UUID.class, conversationId);
        Integer sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence_number),0)+1 FROM messages WHERE conversation_id=?
                """, Integer.class, conversationId);
        jdbc.update("""
                INSERT INTO messages
                (id,company_id,workspace_id,conversation_id,role,content,capability,sequence_number,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), scope.companyId(), scope.workspaceId(), conversationId, role, content,
                capability, sequence == null ? 1 : sequence, createdBy, timestamp(now));
        jdbc.update("UPDATE conversations SET updated_at=? WHERE id=?", timestamp(now), conversationId);
    }

    private int nextAttempt(UUID taskId) {
        Integer attempt = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number),0)+1 FROM ai_runs WHERE recruitment_task_id=?
                """, Integer.class, taskId);
        return attempt == null ? 1 : attempt;
    }

    private void audit(UUID actor, WorkspaceScope scope, String action, String resourceType, UUID resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,company_id,workspace_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), actor, scope.companyId(), scope.workspaceId(), action, resourceType,
                resourceId.toString(), timestamp(Instant.now()));
    }

    private String hash(Object value) {
        try {
            return SecurityHashes.sha256(objectMapper.writeValueAsString(value == null ? Map.of() : value));
        } catch (JsonProcessingException exception) {
            throw validation("请求内容无法处理");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw validation("结构化内容无法保存");
        }
    }

    private List<String> parseWarnings(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new ApiException("JD_DRAFT_CORRUPTED", "JD 草稿结构异常", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static String requiredIdempotencyKey(String key) {
        return required(key, "缺少 Idempotency-Key", 200);
    }

    private static String normalizedScenario(String scenario) {
        String value = scenario == null || scenario.isBlank() ? "NORMAL" : scenario.trim().toUpperCase();
        if (!List.of("NORMAL", "TIMEOUT", "INVALID_SCHEMA").contains(value)) throw validation("无效的 Mock 场景");
        return value;
    }

    private static String required(String value, String message, int max) {
        if (value == null || value.isBlank()) throw validation(message);
        String clean = value.trim();
        if (clean.length() > max) throw validation(message + "且不能超过" + max + "字");
        return clean;
    }

    private static String optional(String value, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > max) throw validation("字段内容过长");
        return clean;
    }

    private static String defaulted(String value, String fallback, int max) {
        return optional(value == null || value.isBlank() ? fallback : value, max);
    }

    private static <T> String value(T object, java.util.function.Function<T, String> getter) {
        return object == null ? null : getter.apply(object);
    }

    private static ApiException validation(String message) {
        return new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    private static ApiException taskNotFound() {
        return new ApiException("RECRUITMENT_TASK_NOT_FOUND", "招聘任务不存在", HttpStatus.NOT_FOUND);
    }

    private static ApiException idempotencyConflict() {
        return new ApiException("IDEMPOTENCY_CONFLICT", "相同幂等键对应的请求内容不一致", HttpStatus.CONFLICT);
    }

    public record CreateTaskInput(String title, String initialRequirement) { }

    public record MessageInput(String content) { }

    public record GenerateJdInput(String requirement, String title, String companyName, String location,
                                  String experienceLevel, String education, String jobType, String skills,
                                  String scenario) { }

    public record UpdateDraftInput(int revision, String title, String companyName, String location,
                                   String experienceLevel, String education, String jobType,
                                   String responsibilities, String requirements, String skills,
                                   String talentProfile, List<String> warnings) { }

    public record TaskSummary(UUID id, UUID companyId, UUID workspaceId, String title, String status,
                              String currentStage, UUID jobId, String jobTitle, UUID createdBy,
                              Instant createdAt, Instant updatedAt) { }

    public record MessageView(UUID id, String role, String content, String capability, int sequenceNumber,
                              UUID createdBy, Instant createdAt) { }

    public record JdDraftView(UUID id, int revision, String title, String companyName, String location,
                              String experienceLevel, String education, String jobType, String responsibilities,
                              String requirements, String skills, String talentProfile, List<String> warnings,
                              String status, Instant updatedAt) { }

    public record AiRunView(UUID id, String providerTaskId, String status, int progress, int attemptNumber,
                            String pricingVersion, long estimatedAmountMinor, long settledAmountMinor,
                            String errorCode, String errorMessage, Instant createdAt, Instant completedAt) { }

    public record TaskDetail(TaskSummary task, UUID conversationId, List<MessageView> messages,
                             JdDraftView jdDraft, AiRunView latestAiRun) { }

    private record ExistingReference(UUID id, String requestHash) { }

    private record TaskRow(UUID id, String initialRequirement, UUID conversationId) { }
}
