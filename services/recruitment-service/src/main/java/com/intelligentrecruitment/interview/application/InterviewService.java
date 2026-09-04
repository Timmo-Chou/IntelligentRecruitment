package com.intelligentrecruitment.interview.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.Competency;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.GenerateInterviewQuestionsInput;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.GenerateInterviewQuestionsInput.CandidateSnapshot;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.GenerateInterviewQuestionsInput.JobSnapshot;
import com.intelligentrecruitment.aiplatform.application.InterviewQuestionContract.InterviewQuestionKit;
import com.intelligentrecruitment.candidates.application.PiiCipher;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

/** Creates editable interview kits from the selected JD version and talent profile. */
@Service
public class InterviewService {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccessService access;
    private final ObjectMapper objectMapper;
    private final AiPlatformClient aiPlatformClient;
    private final PiiCipher pii;

    public InterviewService(JdbcTemplate jdbc, WorkspaceAccessService access, ObjectMapper objectMapper,
                            AiPlatformClient aiPlatformClient, PiiCipher pii) {
        this.jdbc = jdbc;
        this.access = access;
        this.objectMapper = objectMapper;
        this.aiPlatformClient = aiPlatformClient;
        this.pii = pii;
    }

    public List<KitSummary> list(UUID userId, UUID workspaceId) {
        access.requireBusinessAccess(userId, workspaceId);
        return jdbc.query("""
                SELECT k.id,k.candidate_id,c.full_name_ciphertext,k.status,k.created_at,j.title AS job_title
                FROM interview_kits k
                JOIN candidates c ON c.id=k.candidate_id
                LEFT JOIN job_versions jv ON jv.id=k.job_version_id
                LEFT JOIN jobs j ON j.id=jv.job_id
                WHERE k.workspace_id=? ORDER BY k.created_at DESC
                """, (r, n) -> new KitSummary(
                r.getObject("id", UUID.class), r.getObject("candidate_id", UUID.class),
                pii.decrypt(r.getString("full_name_ciphertext")), r.getString("job_title"), r.getString("status"),
                r.getTimestamp("created_at").toInstant()), workspaceId);
    }

    @Transactional
    public KitDetail create(UUID userId, UUID workspaceId, CreateInput input) {
        access.requireBusinessAccess(userId, workspaceId);
        if (input.candidateId() == null) throw badRequest("CANDIDATE_REQUIRED", "请选择人才");
        if (input.jobVersionId() == null) throw badRequest("JOB_REQUIRED", "请选择 JD");
        CandidateContext candidate = candidate(workspaceId, input.candidateId());
        JobContext job = job(workspaceId, input.jobVersionId());
        Instant now = Instant.now();
        UUID kitId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID companyId = jdbc.queryForObject("SELECT company_id FROM workspaces WHERE id=?", UUID.class, workspaceId);

        JobSnapshot jobSnap = buildJobSnapshot(job);
        CandidateSnapshot candSnap = new CandidateSnapshot(candidate.name(), candidate.headline(),
                candidate.skills(), candidate.summary(), "");
        InterviewQuestionKit kit = aiPlatformClient.generateInterviewQuestions(
                new GenerateInterviewQuestionsInput(workspaceId, jobSnap, candSnap, input.questionCount()));
        List<CoreCompetency> competencies = toCoreCompetencies(kit.competencies());
        String summary = kit.matchSummary();
        List<Question> questions = toQuestions(kit.questions());

        jdbc.update("""
                INSERT INTO interview_kits(id,company_id,workspace_id,job_version_id,candidate_id,screening_result_id,status,
                                           core_competencies,match_summary,created_by,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'DRAFT',?::jsonb,?,?,?,?)
                """, kitId, companyId, workspaceId, input.jobVersionId(), input.candidateId(), input.screeningResultId(),
                protectedJson(competencies), pii.encrypt(summary), userId, timestamp(now), timestamp(now));
        jdbc.update("""
                INSERT INTO interview_kit_versions(id,company_id,workspace_id,kit_id,screening_result_id,version_no,status,created_by,created_at)
                VALUES(?,?,?,?,?,1,'DRAFT',?,?)
                """, versionId, companyId, workspaceId, kitId, input.screeningResultId(), userId, timestamp(now));
        insertQuestions(versionId, companyId, workspaceId, questions);
        return get(userId, workspaceId, kitId);
    }

    @Transactional
    public KitDetail update(UUID userId, UUID workspaceId, UUID kitId, List<QuestionInput> questions) {
        access.requireBusinessAccess(userId, workspaceId);
        requireKit(workspaceId, kitId);
        if (questions == null || questions.isEmpty()) throw badRequest("QUESTIONS_REQUIRED", "请至少保留一道面试题");
        UUID companyId = jdbc.queryForObject("SELECT company_id FROM workspaces WHERE id=?", UUID.class, workspaceId);
        VersionContext current = jdbc.queryForObject("""
                SELECT id,screening_result_id,version_no FROM interview_kit_versions
                WHERE kit_id=? AND workspace_id=? ORDER BY version_no DESC LIMIT 1
                """, (r, n) -> new VersionContext(r.getObject("id", UUID.class), r.getObject("screening_result_id", UUID.class), r.getInt("version_no")), kitId, workspaceId);
        UUID nextVersion = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_kit_versions(id,company_id,workspace_id,kit_id,screening_result_id,version_no,status,created_by,created_at)
                VALUES(?,?,?,?,?,?,'DRAFT',?,?)
                """, nextVersion, companyId, workspaceId, kitId, current.screeningResultId(), current.versionNo() + 1, userId, timestamp(Instant.now()));
        List<Question> normalized = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) {
            QuestionInput q = questions.get(i);
            if (blank(q.content())) throw badRequest("QUESTION_CONTENT_REQUIRED", "面试题目不能为空");
            normalized.add(new Question(UUID.randomUUID(), value(q.category(), "综合评估"), q.content().trim(), value(q.rationale(), "岗位胜任能力核验"),
                    value(q.focusPoints(), "能力证据与思考过程"), value(q.referenceAnswerPoints(), "结合真实经历说明方法、行动与结果"),
                    value(q.scoringPoints(), "回答完整、证据具体、结果可信"), value(q.evidenceRefs(), "JD 与人才档案"), i));
        }
        insertQuestions(nextVersion, companyId, workspaceId, normalized);
        jdbc.update("UPDATE interview_kits SET status='DRAFT',updated_at=? WHERE id=? AND workspace_id=?", timestamp(Instant.now()), kitId, workspaceId);
        return get(userId, workspaceId, kitId);
    }

    @Transactional
    public KitDetail confirm(UUID userId, UUID workspaceId, UUID kitId) {
        access.requireBusinessAccess(userId, workspaceId);
        requireKit(workspaceId, kitId);
        Instant now = Instant.now();
        jdbc.update("UPDATE interview_kits SET status='CONFIRMED',updated_at=? WHERE id=? AND workspace_id=?", timestamp(now), kitId, workspaceId);
        jdbc.update("UPDATE interview_kit_versions SET status='CONFIRMED' WHERE kit_id=? AND workspace_id=? AND version_no=(SELECT MAX(version_no) FROM interview_kit_versions WHERE kit_id=? AND workspace_id=?)", kitId, workspaceId, kitId, workspaceId);
        return get(userId, workspaceId, kitId);
    }

    public KitDetail get(UUID userId, UUID workspaceId, UUID kitId) {
        access.requireBusinessAccess(userId, workspaceId);
        KitContext kit = requireKit(workspaceId, kitId);
        List<Question> questions = jdbc.query("""
                SELECT iq.id,iq.category,iq.content,iq.rationale,iq.focus_points,iq.reference_answer_points,iq.scoring_points,iq.evidence_refs,iq.sort_order
                FROM interview_questions iq
                JOIN interview_kit_versions v ON v.id=iq.kit_version_id
                WHERE v.kit_id=? AND v.workspace_id=? AND v.version_no=(SELECT MAX(version_no) FROM interview_kit_versions WHERE kit_id=? AND workspace_id=?)
                ORDER BY iq.sort_order
                """, (r, n) -> new Question(r.getObject("id", UUID.class), r.getString("category"), pii.decryptIfEncrypted(r.getString("content")), pii.decryptIfEncrypted(r.getString("rationale")),
                pii.decryptIfEncrypted(r.getString("focus_points")), pii.decryptIfEncrypted(r.getString("reference_answer_points")), pii.decryptIfEncrypted(r.getString("scoring_points")), pii.decryptIfEncrypted(r.getString("evidence_refs")), r.getInt("sort_order")), kitId, workspaceId, kitId, workspaceId);
        return new KitDetail(kit.id(), kit.jobTitle(), kit.candidateName(), kit.status(), parseCompetencies(kit.coreCompetencies()), kit.matchSummary(), questions);
    }

    private KitContext requireKit(UUID workspaceId, UUID kitId) {
        List<KitContext> kits = jdbc.query("""
                SELECT k.id,k.status,k.core_competencies::text,k.match_summary,j.title AS job_title,c.full_name_ciphertext
                FROM interview_kits k JOIN candidates c ON c.id=k.candidate_id
                LEFT JOIN job_versions jv ON jv.id=k.job_version_id LEFT JOIN jobs j ON j.id=jv.job_id
                WHERE k.id=? AND k.workspace_id=?
                """, (r, n) -> new KitContext(r.getObject("id", UUID.class), r.getString("status"), r.getString("core_competencies"), pii.decryptIfEncrypted(r.getString("match_summary")), r.getString("job_title"), pii.decrypt(r.getString("full_name_ciphertext"))), kitId, workspaceId);
        if (kits.isEmpty()) throw new ApiException("INTERVIEW_KIT_NOT_FOUND", "面试题包不存在", HttpStatus.NOT_FOUND);
        return kits.getFirst();
    }

    private JobContext job(UUID workspaceId, UUID versionId) {
        List<JobContext> jobs = jdbc.query("""
                SELECT j.title,jv.snapshot::text FROM job_versions jv JOIN jobs j ON j.id=jv.job_id
                WHERE jv.id=? AND jv.workspace_id=?
                """, (r, n) -> new JobContext(r.getString("title"), r.getString("snapshot")), versionId, workspaceId);
        if (jobs.isEmpty()) throw new ApiException("JOB_NOT_FOUND", "JD 不存在或无权访问", HttpStatus.NOT_FOUND);
        return jobs.getFirst();
    }

    private CandidateContext candidate(UUID workspaceId, UUID candidateId) {
        List<CandidateContext> candidates = jdbc.query("""
                SELECT c.full_name_ciphertext,
                       COALESCE(rp.headline,'') AS headline,
                       COALESCE(rp.skills,'[]'::jsonb)::text AS skills,
                       COALESCE(rp.summary,'') AS summary
                FROM candidates c LEFT JOIN resume_parse_versions rp ON rp.id=c.current_parse_version_id
                WHERE c.id=? AND c.workspace_id=?
                """, (r, n) -> new CandidateContext(pii.decrypt(r.getString("full_name_ciphertext")), r.getString("headline"), strings(r.getString("skills")), r.getString("summary")), candidateId, workspaceId);
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

    private void insertQuestions(UUID versionId, UUID companyId, UUID workspaceId, List<Question> questions) {
        for (Question q : questions) jdbc.update("""
                INSERT INTO interview_questions(id,company_id,workspace_id,kit_version_id,category,content,rationale,focus_points,reference_answer_points,scoring_points,evidence_refs,sort_order)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, q.id(), companyId, workspaceId, versionId, q.category(), pii.encrypt(q.content()), pii.encrypt(q.rationale()), pii.encrypt(q.focusPoints()), pii.encrypt(q.referenceAnswerPoints()), pii.encrypt(q.scoringPoints()), pii.encrypt(q.evidenceRefs()), q.sortOrder());
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

    // ====== AI 面试题输入/输出转换（把 JobContext → JobSnapshot；AiPlatform 返回 → InterviewService 领域类型） ======

    /** 从 job_versions.snapshot 读出 JD 详情 → JobSnapshot（供 AI 平台 Prompt 构造） */
    private JobSnapshot buildJobSnapshot(JobContext job) {
        String title = value(job.title(), "");
        String responsibilities = textAt(job.snapshot(), "responsibilities");
        String requirements = textAt(job.snapshot(), "requirements");
        String skills = textAt(job.snapshot(), "skills");
        String company = textAt(job.snapshot(), "company_name");
        String location = textAt(job.snapshot(), "location");
        String exp = textAt(job.snapshot(), "experience_level");
        String edu = textAt(job.snapshot(), "education");
        return new JobSnapshot(title, company, location, exp, edu, responsibilities, requirements, skills);
    }

    /** AI 返回的 3 项胜任力 → 领域模型 CoreCompetency */
    private List<CoreCompetency> toCoreCompetencies(List<Competency> list) {
        if (list == null || list.isEmpty()) return List.of(
                new CoreCompetency("岗位专业能力", "验证岗位相关的专业方法和业务理解"),
                new CoreCompetency("项目交付与问题解决", "验证问题拆解、协同推进和结果复盘能力"),
                new CoreCompetency("协作与沟通", "验证跨团队协作、冲突处理与汇报能力"));
        List<CoreCompetency> out = new ArrayList<>();
        for (Competency c : list) {
            if (c == null || c.name() == null || c.name().isBlank()) continue;
            out.add(new CoreCompetency(c.name().trim(), value(c.description(), "考察「" + c.name().trim() + "」的落地深度与真实结果")));
            if (out.size() >= 3) break;
        }
        while (out.size() < 3) out.add(new CoreCompetency("岗位专业能力", "验证岗位相关的专业方法和业务理解"));
        return out.subList(0, 3);
    }

    /** AI 返回的题目列表 → 领域模型 Question（补 UUID、sortOrder） */
    private List<Question> toQuestions(List<InterviewQuestionContract.Question> list) {
        List<Question> out = new ArrayList<>();
        if (list == null || list.isEmpty()) return out;
        int idx = 0;
        for (InterviewQuestionContract.Question q : list) {
            if (q == null || blank(q.content())) continue;
            out.add(new Question(
                    UUID.randomUUID(),
                    value(q.category(), "综合评估"),
                    q.content().trim(),
                    value(q.rationale(), "岗位胜任能力核验"),
                    value(q.focusPoints(), "背景与本人角色；方法与决策依据；量化结果；风险识别与复盘"),
                    value(q.referenceAnswerPoints(), "结合真实经历说明方法、行动与结果"),
                    value(q.scoringPoints(), "回答完整、证据具体、结果可信"),
                    value(q.evidenceRefs(), "JD 与人才档案"),
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
