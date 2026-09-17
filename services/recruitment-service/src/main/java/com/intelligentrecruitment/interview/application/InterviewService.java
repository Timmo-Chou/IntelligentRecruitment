package com.intelligentrecruitment.interview.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.Competency;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.InterviewQuestionKit;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.candidates.application.PiiCipher;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.tenancy.application.TenantAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

/** Creates editable interview kits from the selected JD version and talent profile. */
@Service
public class InterviewService {
    private final JdbcTemplate jdbc;
    private final TenantAccessService access;
    private final ObjectMapper objectMapper;
    private final PiiCipher pii;

    public InterviewService(JdbcTemplate jdbc, TenantAccessService access, ObjectMapper objectMapper, PiiCipher pii) {
        this.jdbc = jdbc;
        this.access = access;
        this.objectMapper = objectMapper;
        this.pii = pii;
    }

    public List<KitSummary> list(UUID userId, UUID tenantId) {
        access.requireBusinessAccess(userId, tenantId);
        return jdbc.query("""
                SELECT k.id,k.candidate_id,c.full_name_ciphertext,k.status,k.created_at,j.title AS job_title
                FROM interview_kits k
                JOIN candidates c ON c.id=k.candidate_id
                LEFT JOIN job_versions jv ON jv.id=k.job_version_id
                LEFT JOIN jobs j ON j.id=jv.job_id
                WHERE k.tenant_id=? ORDER BY k.created_at DESC
                """, (r, n) -> new KitSummary(
                r.getObject("id", UUID.class), r.getObject("candidate_id", UUID.class),
                pii.decrypt(r.getString("full_name_ciphertext")), r.getString("job_title"), r.getString("status"),
                r.getTimestamp("created_at").toInstant()), tenantId);
    }

    public Map<String, Object> buildAiInput(UUID userId, UUID tenantId, CreateInput input) {
        access.requireBusinessAccess(userId, tenantId);
        return buildAuthorizedAiInput(tenantId, input);
    }

    /** Builds input from tenant-scoped records for an already authorized queued task. */
    public Map<String, Object> buildAuthorizedAiInput(UUID tenantId, CreateInput input) {
        if (input.candidateId() == null) throw badRequest("CANDIDATE_REQUIRED", "请选择人才");
        if (input.jobVersionId() == null) throw badRequest("JOB_REQUIRED", "请选择 JD");
        CandidateContext candidate = candidate(tenantId, input.candidateId());
        JobContext job = job(tenantId, input.jobVersionId());
        return Map.of("job", Map.of("title", job.title(), "company_name", textAt(job.snapshot(), "company_name"),
                        "location", textAt(job.snapshot(), "location"), "experience_level", textAt(job.snapshot(), "experience_level"),
                        "education", textAt(job.snapshot(), "education"), "responsibilities", textAt(job.snapshot(), "responsibilities"),
                        "requirements", textAt(job.snapshot(), "requirements"), "skills", textAt(job.snapshot(), "skills")),
                "candidate", Map.of("name", candidate.name(), "headline", candidate.headline(), "skills", candidate.skills(),
                        "summary", candidate.summary(), "resume_text", ""),
                "requested_count", Math.max(4, Math.min(input.questionCount() <= 0 ? 8 : input.questionCount(), 20)),
                "language_hint", "中文");
    }

    @Transactional
    public KitDetail persistAiResult(UUID userId, UUID tenantId, CreateInput input, StructuredResult result) {
        access.requireBusinessAccess(userId, tenantId);
        return persistAuthorizedAiResult(userId, tenantId, input, result);
    }

    /**
     * Persists a result that has already passed the BOSS-authorized AIAgent task.
     * The worker still scopes every source row to its tenant; it does not perform
     * a second interactive access check because there is no user request context.
     */
    @Transactional
    public KitDetail persistAuthorizedAiResult(UUID userId, UUID tenantId, CreateInput input, StructuredResult result) {
        if (result == null || result.data() == null) throw badRequest("AI_SCHEMA_INVALID", "AI 面试题未返回结构化结果");
        InterviewQuestionKit kit = parseAiKit(result.data(), input.questionCount());
        Instant now = Instant.now();
        UUID kitId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        List<CoreCompetency> competencies = toCoreCompetencies(kit.competencies());
        String summary = kit.matchSummary();
        List<Question> questions = toQuestions(kit.questions());

        jdbc.update("""
                INSERT INTO interview_kits(id,tenant_id,job_version_id,candidate_id,screening_result_id,status,
                                           core_competencies,match_summary,created_by,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'DRAFT',?::jsonb,?,?,?,?)
                """, kitId, tenantId, input.jobVersionId(), input.candidateId(), input.screeningResultId(),
                protectedJson(competencies), pii.encrypt(summary), userId, timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO interview_kit_versions(id,tenant_id,kit_id,screening_result_id,version_no,status,created_by,created_at)
                VALUES(?,?,?,?,?,1,'DRAFT',?,?)
                """, versionId, tenantId, kitId, input.screeningResultId(), userId, timestamp(now));
        insertQuestions(versionId, tenantId, questions);
        return getAuthorized(tenantId, kitId);
    }

    @Transactional
    public KitDetail update(UUID userId, UUID tenantId, UUID kitId, List<QuestionInput> questions) {
        access.requireBusinessAccess(userId, tenantId);
        requireKit(tenantId, kitId);
        if (questions == null || questions.isEmpty()) throw badRequest("QUESTIONS_REQUIRED", "请至少保留一道面试题");
        VersionContext current = jdbc.queryForObject("""
                SELECT id,screening_result_id,version_no FROM interview_kit_versions
                WHERE kit_id=? AND tenant_id=? ORDER BY version_no DESC LIMIT 1
                """, (r, n) -> new VersionContext(r.getObject("id", UUID.class), r.getObject("screening_result_id", UUID.class), r.getInt("version_no")), kitId, tenantId);
        UUID nextVersion = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_kit_versions(id,tenant_id,kit_id,screening_result_id,version_no,status,created_by,created_at)
                VALUES(?,?,?,?,?,?,'DRAFT',?,?)
                """, nextVersion, tenantId, kitId, current.screeningResultId(), current.versionNo() + 1, userId, timestamp(Instant.now()));
        List<Question> normalized = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            QuestionInput q = questions.get(i);
            if (q == null || blank(q.category()) || blank(q.content()) || blank(q.rationale()) || blank(q.focusPoints())
                    || blank(q.referenceAnswerPoints()) || blank(q.scoringPoints()) || blank(q.evidenceRefs())) {
                throw badRequest("QUESTION_FIELDS_REQUIRED", "面试题字段不能为空");
            }
            normalized.add(new Question(UUID.randomUUID(), q.category().trim(), q.content().trim(), q.rationale().trim(),
                    q.focusPoints().trim(), q.referenceAnswerPoints().trim(), q.scoringPoints().trim(), q.evidenceRefs().trim(), i));
        }
        insertQuestions(nextVersion, tenantId, normalized);
        jdbc.update("UPDATE interview_kits SET status='DRAFT',updated_at=? WHERE id=? AND tenant_id=?", timestamp(Instant.now()), kitId, tenantId);
        return get(userId, tenantId, kitId);
    }

    @Transactional
    public KitDetail confirm(UUID userId, UUID tenantId, UUID kitId) {
        access.requireBusinessAccess(userId, tenantId);
        requireKit(tenantId, kitId);
        Instant now = Instant.now();
        jdbc.update("UPDATE interview_kits SET status='CONFIRMED',updated_at=? WHERE id=? AND tenant_id=?", timestamp(now), kitId, tenantId);
        jdbc.update("UPDATE interview_kit_versions SET status='CONFIRMED' WHERE kit_id=? AND tenant_id=? AND version_no=(SELECT MAX(version_no) FROM interview_kit_versions WHERE kit_id=? AND tenant_id=?)", kitId, tenantId, kitId, tenantId);
        return get(userId, tenantId, kitId);
    }

    public KitDetail get(UUID userId, UUID tenantId, UUID kitId) {
        access.requireBusinessAccess(userId, tenantId);
        return getAuthorized(tenantId, kitId);
    }

    private KitDetail getAuthorized(UUID tenantId, UUID kitId) {
        KitContext kit = requireKit(tenantId, kitId);
        List<Question> questions = jdbc.query("""
                SELECT iq.id,iq.category,iq.content,iq.rationale,iq.focus_points,iq.reference_answer_points,iq.scoring_points,iq.evidence_refs,iq.sort_order
                FROM interview_questions iq
                JOIN interview_kit_versions v ON v.id=iq.kit_version_id
                WHERE v.kit_id=? AND v.tenant_id=? AND v.version_no=(SELECT MAX(version_no) FROM interview_kit_versions WHERE kit_id=? AND tenant_id=?)
                ORDER BY iq.sort_order
                """, (r, n) -> new Question(r.getObject("id", UUID.class), r.getString("category"), pii.decryptIfEncrypted(r.getString("content")), pii.decryptIfEncrypted(r.getString("rationale")),
                pii.decryptIfEncrypted(r.getString("focus_points")), pii.decryptIfEncrypted(r.getString("reference_answer_points")), pii.decryptIfEncrypted(r.getString("scoring_points")), pii.decryptIfEncrypted(r.getString("evidence_refs")), r.getInt("sort_order")), kitId, tenantId, kitId, tenantId);
        return new KitDetail(kit.id(), kit.jobTitle(), kit.candidateName(), kit.status(), parseCompetencies(kit.coreCompetencies()), kit.matchSummary(), questions);
    }

    private KitContext requireKit(UUID tenantId, UUID kitId) {
        List<KitContext> kits = jdbc.query("""
                SELECT k.id,k.status,k.core_competencies::text,k.match_summary,j.title AS job_title,c.full_name_ciphertext
                FROM interview_kits k JOIN candidates c ON c.id=k.candidate_id
                LEFT JOIN job_versions jv ON jv.id=k.job_version_id LEFT JOIN jobs j ON j.id=jv.job_id
                WHERE k.id=? AND k.tenant_id=?
                """, (r, n) -> new KitContext(r.getObject("id", UUID.class), r.getString("status"), r.getString("core_competencies"), pii.decryptIfEncrypted(r.getString("match_summary")), r.getString("job_title"), pii.decrypt(r.getString("full_name_ciphertext"))), kitId, tenantId);
        if (kits.isEmpty()) throw new ApiException("INTERVIEW_KIT_NOT_FOUND", "面试题包不存在", HttpStatus.NOT_FOUND);
        return kits.getFirst();
    }

    private JobContext job(UUID tenantId, UUID versionId) {
        List<JobContext> jobs = jdbc.query("""
                SELECT j.title,jv.snapshot::text FROM job_versions jv JOIN jobs j ON j.id=jv.job_id
                WHERE jv.id=? AND jv.tenant_id=?
                """, (r, n) -> new JobContext(r.getString("title"), r.getString("snapshot")), versionId, tenantId);
        if (jobs.isEmpty()) throw new ApiException("JOB_NOT_FOUND", "JD 不存在或无权访问", HttpStatus.NOT_FOUND);
        return jobs.getFirst();
    }

    private CandidateContext candidate(UUID tenantId, UUID candidateId) {
        List<CandidateContext> candidates = jdbc.query("""
                SELECT c.full_name_ciphertext,
                       COALESCE(rp.headline,'') AS headline,
                       COALESCE(rp.skills,'[]'::jsonb)::text AS skills,
                       COALESCE(rp.summary,'') AS summary
                FROM candidates c LEFT JOIN resume_parse_versions rp ON rp.id=c.current_parse_version_id
                WHERE c.id=? AND c.tenant_id=?
                """, (r, n) -> new CandidateContext(pii.decrypt(r.getString("full_name_ciphertext")), r.getString("headline"), strings(r.getString("skills")), r.getString("summary")), candidateId, tenantId);
        if (candidates.isEmpty()) throw new ApiException("CANDIDATE_NOT_FOUND", "人才不存在或无权访问", HttpStatus.NOT_FOUND);
        return candidates.getFirst();
    }

    private List<CoreCompetency> competencies(JobContext job) {
        List<String> skills = stringsAt(job.snapshot(), "skills");
        if (skills.isEmpty()) skills = split(textAt(job.snapshot(), "requirements"));
        List<CoreCompetency> output = new ArrayList<>();
        for (String skill : skills) {
            if (skill.length() > 1 && output.size() < 3) output.add(new CoreCompetency(skill, "验证候选人在「" + skill + "」上的真实经验、方法与交付结果"));
        }
        if (output.size() < 3) output.add(new CoreCompetency("岗位专业能力", "验证与「" + job.title() + "」相关的专业方法和业务理解"));
        if (output.size() < 3) output.add(new CoreCompetency("项目交付与问题解决", "验证复杂问题拆解、协同推进和结果复盘能力"));
        if (output.size() < 3) output.add(new CoreCompetency("沟通协作与成长性", "验证跨团队协作、反馈吸收和持续成长能力"));
        return output.subList(0, 3);
    }

    private String matchSummary(JobContext job, CandidateContext candidate, List<CoreCompetency> competencies) {
        List<String> hit = candidate.skills().stream().filter(skill -> competencies.stream().anyMatch(c -> c.name().toLowerCase().contains(skill.toLowerCase()) || skill.toLowerCase().contains(c.name().toLowerCase()))).toList();
        String experience = blank(candidate.headline()) ? "人才档案中的经历" : candidate.headline();
        String evidence = hit.isEmpty() ? "尚未在已解析技能中发现直接对应项，建议通过项目案例核验" : "已呈现相关技能：" + String.join("、", hit) + "，建议进一步核验深度与产出";
        return "候选人「" + candidate.name() + "」的定位为「" + experience + "」。与 JD「" + job.title() + "」相比，" + evidence + "；面试应重点围绕 " + String.join("、", competencies.stream().map(CoreCompetency::name).toList()) + " 收集可验证证据。";
    }

    private List<Question> generatedQuestions(JobContext job, CandidateContext candidate, List<CoreCompetency> competencies, int requested) {
        int count = Math.max(4, Math.min(requested <= 0 ? 8 : requested, 20));
        String[] types = {"专业能力", "项目实践", "行为协作", "场景决策"};
        List<Question> questions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CoreCompetency c = competencies.get(i % competencies.size());
            String type = types[i % types.length];
            String content = switch (type) {
                case "专业能力" -> "请结合你在「" + c.name() + "」上的实际经历，说明你如何完成一个与「" + job.title() + "」相关的关键任务。";
                case "项目实践" -> "请选取一个最能体现「" + c.name() + "」的项目，说明目标、你的职责、关键行动、量化结果及复盘。";
                case "行为协作" -> "在推进与「" + c.name() + "」有关的工作时，你遇到过哪些协作分歧？你如何推动达成共识？";
                default -> "假设入职后需要在有限时间内解决「" + c.name() + "」问题，你会如何判断优先级、制定方案并验证结果？";
            };
            questions.add(new Question(UUID.randomUUID(), type, content, c.description(), c.name() + "：" + c.description(),
                    "说明真实背景与本人角色；交代具体方法、决策依据和量化结果；能够复盘风险与改进。",
                    "5分：证据充分、方法成熟且结果可验证；3分：经历真实、方法基本合理；1分：描述笼统或无法说明本人贡献。",
                    "JD：" + job.title() + "；人才：" + candidate.name(), i));
        }
        return questions;
    }

    private void insertQuestions(UUID versionId, UUID tenantId, List<Question> questions) {
        for (Question q : questions) jdbc.update("""
                INSERT INTO interview_questions(id,tenant_id,kit_version_id,category,content,rationale,focus_points,reference_answer_points,scoring_points,evidence_refs,sort_order)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, q.id(), tenantId, versionId, q.category(), pii.encrypt(q.content()), pii.encrypt(q.rationale()), pii.encrypt(q.focusPoints()), pii.encrypt(q.referenceAnswerPoints()), pii.encrypt(q.scoringPoints()), pii.encrypt(q.evidenceRefs()), q.sortOrder());
    }

    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("无法保存面试题能力模型", e); } }
    private String protectedJson(Object value) { return json(java.util.Map.of("_encrypted", pii.encrypt(json(value)))); }
    private List<CoreCompetency> parseCompetencies(String value) { try { JsonNode node = objectMapper.readTree(value == null ? "[]" : value); String json = node.has("_encrypted") ? pii.decryptIfEncrypted(node.path("_encrypted").asText()) : value; return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() {}); } catch (Exception e) { return List.of(); } }
    private List<String> strings(String value) { try { return objectMapper.readValue(value == null ? "[]" : value, new TypeReference<List<String>>() {}); } catch (Exception e) { return split(value); } }
    private List<String> stringsAt(String json, String field) { try { JsonNode n = objectMapper.readTree(json).path(field); if (n.isArray()) { List<String> r = new ArrayList<>(); n.forEach(v -> r.add(v.asText())); return r; } return split(n.asText("")); } catch (Exception e) { return List.of(); } }
    private String textAt(String json, String field) { try { return objectMapper.readTree(json).path(field).asText(""); } catch (Exception e) { return ""; } }
    private List<String> split(String value) { if (blank(value)) return List.of(); LinkedHashSet<String> values = new LinkedHashSet<>(); for (String v : value.replaceAll("[\\[\\]\"]", "").split("[、,，;；/\\n]+")) if (!blank(v) && v.trim().length() <= 30) values.add(v.trim()); return List.copyOf(values); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String value(String candidate, String fallback) { return blank(candidate) ? fallback : candidate.trim(); }
    private ApiException badRequest(String code, String message) { return new ApiException(code, message, HttpStatus.BAD_REQUEST); }

    private InterviewQuestionKit parseAiKit(java.util.Map<String, Object> data, int requestedCount) {
        JsonNode root = objectMapper.valueToTree(data);
        String summary = root.path("match_summary").asText("").trim();
        if (summary.isBlank()) throw badRequest("AI_SCHEMA_INVALID", "AI 面试题缺少 match_summary");
        List<Competency> competencies = new ArrayList<>();
        for (JsonNode item : root.path("core_competencies")) {
            String name = item.path("name").asText("").trim();
            String description = item.path("description").asText("").trim();
            if (!name.isBlank() && !description.isBlank()) competencies.add(new Competency(name, description));
        }
        if (competencies.size() != 3) throw badRequest("AI_SCHEMA_INVALID", "AI 面试题必须返回 3 项核心胜任力");
        List<InterviewQuestionContract.Question> questions = new ArrayList<>();
        for (JsonNode item : root.path("questions")) {
            String content = item.path("content").asText("").trim();
            if (content.isBlank()) throw badRequest("AI_SCHEMA_INVALID", "AI 面试题存在空题面");
            questions.add(new InterviewQuestionContract.Question(item.path("category").asText(""), content,
                    item.path("rationale").asText(""), item.path("focus_points").asText(""),
                    item.path("reference_answer_points").asText(""), item.path("scoring_points").asText(""),
                    item.path("evidence_refs").asText(""), item.path("core_competency").asText("")));
        }
        int expected = Math.max(4, Math.min(requestedCount <= 0 ? 8 : requestedCount, 20));
        if (questions.size() != expected) throw badRequest("AI_SCHEMA_INVALID", "AI 面试题数量与请求数量不一致");
        return new InterviewQuestionKit(summary, competencies, questions);
    }

    /** AI 返回的 3 项胜任力 → 领域模型 CoreCompetency */
    private List<CoreCompetency> toCoreCompetencies(List<Competency> list) {
        if (list == null || list.size() != 3) throw badRequest("AI_SCHEMA_INVALID", "AI 面试题核心胜任力数量不正确");
        List<CoreCompetency> out = new ArrayList<>();
        for (Competency c : list) {
            if (c == null || c.name() == null || c.name().isBlank()) continue;
            if (c.description() == null || c.description().isBlank()) throw badRequest("AI_SCHEMA_INVALID", "AI 面试题核心胜任力说明为空");
            out.add(new CoreCompetency(c.name().trim(), c.description().trim()));
        }
        if (out.size() != 3) throw badRequest("AI_SCHEMA_INVALID", "AI 面试题核心胜任力无效");
        return out;
    }

    /** AI 返回的题目列表 → 领域模型 Question（补 UUID、sortOrder） */
    private List<Question> toQuestions(List<InterviewQuestionContract.Question> list) {
        List<Question> out = new ArrayList<>();
        if (list == null || list.isEmpty()) return out;
        int idx = 0;
        for (InterviewQuestionContract.Question q : list) {
            if (q == null || blank(q.content()) || blank(q.category()) || blank(q.rationale())
                    || blank(q.focusPoints()) || blank(q.referenceAnswerPoints())
                    || blank(q.scoringPoints()) || blank(q.evidenceRefs())) {
                throw badRequest("AI_SCHEMA_INVALID", "AI 面试题字段不完整");
            }
            out.add(new Question(
                    UUID.randomUUID(),
                    q.category().trim(),
                    q.content().trim(),
                    q.rationale().trim(),
                    q.focusPoints().trim(),
                    q.referenceAnswerPoints().trim(),
                    q.scoringPoints().trim(),
                    q.evidenceRefs().trim(),
                    idx++));
        }
        return out;
    }

    private record JobContext(String title, String snapshot) {}
    private record CandidateContext(String name, String headline, List<String> skills, String summary) {}
    private record VersionContext(UUID id, UUID screeningResultId, int versionNo) {}
    private record KitContext(UUID id, String status, String coreCompetencies, String matchSummary, String jobTitle, String candidateName) {}
    public record CreateInput(UUID candidateId, UUID jobVersionId, UUID screeningResultId, int questionCount) {}
    public record CoreCompetency(String name, String description) {}
    public record QuestionInput(String category, String content, String rationale, String focusPoints, String referenceAnswerPoints, String scoringPoints, String evidenceRefs) {}
    public record Question(UUID id, String category, String content, String rationale, String focusPoints, String referenceAnswerPoints, String scoringPoints, String evidenceRefs, int sortOrder) {}
    public record KitSummary(UUID id, UUID candidateId, String candidateName, String jobTitle, String status, Instant createdAt) {}
    public record KitDetail(UUID id, String jobTitle, String candidateName, String status, List<CoreCompetency> coreCompetencies, String matchSummary, List<Question> questions) {}
}
