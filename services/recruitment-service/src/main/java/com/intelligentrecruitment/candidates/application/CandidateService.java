package com.intelligentrecruitment.candidates.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.candidates.infrastructure.ResumeObjectStorage;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService.WorkspaceScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class CandidateService {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern YEARS = Pattern.compile("(\\d{1,2})\\s*年");
    private static final List<String> KNOWN_SKILLS = List.of("Java", "Spring Boot", "Spring Cloud", "MySQL",
            "Redis", "Kafka", "Python", "Go", "React", "Vue", "TypeScript", "JavaScript", "Docker",
            "Kubernetes", "产品设计", "需求分析", "数据分析", "项目管理", "自动化测试");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessService workspaceAccess;
    private final ResumeObjectStorage storage;
    private final ResumeTextExtractor extractor;
    private final PiiCipher pii;
    private final long maxFileSize;

    public CandidateService(JdbcTemplate jdbc, ObjectMapper objectMapper, WorkspaceAccessService workspaceAccess,
                            ResumeObjectStorage storage, ResumeTextExtractor extractor, PiiCipher pii,
                            @Value("${app.storage.max-file-size-bytes:10485760}") long maxFileSize) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workspaceAccess = workspaceAccess;
        this.storage = storage;
        this.extractor = extractor;
        this.pii = pii;
        this.maxFileSize = maxFileSize;
    }

    @Transactional
    public CandidateDetail upload(UUID userId, UUID workspaceId, MultipartFile file, String scenario) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        byte[] bytes = validateAndRead(file);
        String hash = SecurityHashes.sha256(bytes);
        List<UUID> duplicate = jdbc.query("""
                SELECT c.id FROM candidates c JOIN resume_files rf ON rf.candidate_id=c.id
                JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE c.workspace_id=? AND f.sha256=? AND c.status<>'DELETED'
                """, (rs, n) -> rs.getObject(1, UUID.class), workspaceId, hash);
        if (!duplicate.isEmpty()) return detailScoped(workspaceId, duplicate.getFirst());

        UUID assetId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID resumeFileId = UUID.randomUUID();
        String filename = safeFilename(file.getOriginalFilename());
        String objectKey = workspaceId + "/" + assetId + "/" + filename;
        String mediaType = mediaType(filename);
        storage.put(objectKey, bytes, mediaType);
        try {
            return persistUpload(userId, scope, assetId, candidateId, resumeFileId, objectKey, filename,
                    mediaType, bytes, hash, normalizeScenario(scenario));
        } catch (RuntimeException exception) {
            try { storage.remove(objectKey); } catch (RuntimeException ignored) { }
            throw exception;
        }
    }

    protected CandidateDetail persistUpload(UUID userId, WorkspaceScope scope, UUID assetId, UUID candidateId,
                                            UUID resumeFileId, String objectKey, String filename, String mediaType,
                                            byte[] bytes, String hash, String scenario) {
        Instant now = Instant.now();
        String rawText = extractor.extract(bytes, filename);
        ParsedResume parsed = parse(filename, rawText);
        jdbc.update("""
                INSERT INTO file_assets
                (id,company_id,workspace_id,object_key,original_filename,media_type,size_bytes,sha256,
                 scan_status,lifecycle_status,created_by,created_at)
                VALUES (?,?,?,?,?,?,?,?, 'CLEAN','ACTIVE',?,?)
                """, assetId, scope.companyId(), scope.workspaceId(), objectKey, filename, mediaType, bytes.length,
                hash, userId, timestamp(now));
        jdbc.update("""
                INSERT INTO candidates
                (id,company_id,workspace_id,display_name_masked,full_name_ciphertext,email_ciphertext,
                 phone_ciphertext,status,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,?)
                """, candidateId, scope.companyId(), scope.workspaceId(), mask(parsed.name()), pii.encrypt(parsed.name()),
                pii.encrypt(parsed.email()), pii.encrypt(parsed.phone()), userId, timestamp(now), timestamp(now));
        String status = "INVALID_SCHEMA".equals(scenario) ? "PARSE_FAILED" : "PARSED";
        jdbc.update("""
                INSERT INTO resume_files
                (id,company_id,workspace_id,candidate_id,file_asset_id,status,error_code,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?, ?,?,?)
                """, resumeFileId, scope.companyId(), scope.workspaceId(), candidateId, assetId, status,
                "INVALID_SCHEMA".equals(scenario) ? "AI_SCHEMA_INVALID" : null, userId, timestamp(now), timestamp(now));
        if (!"INVALID_SCHEMA".equals(scenario)) saveParseVersion(scope, candidateId, resumeFileId, 1, parsed, now);
        audit(userId, scope, "RESUME_UPLOADED", candidateId);
        return detailScoped(scope.workspaceId(), candidateId);
    }

    public CandidateListResult list(UUID userId, UUID workspaceId, String search, String status, int page, int pageSize) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
        StringBuilder where = new StringBuilder(" WHERE c.workspace_id=? AND c.status<>'DELETED'");
        if (search != null && !search.isBlank()) {
            where.append(" AND (c.display_name_masked ILIKE ? OR pv.headline ILIKE ? OR pv.skills::text ILIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like); params.add(like); params.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND rf.status=?");
            params.add(status.toUpperCase(Locale.ROOT));
        }
        String joins = """
                FROM candidates c LEFT JOIN resume_parse_versions pv ON pv.id=c.current_parse_version_id
                LEFT JOIN resume_files rf ON rf.candidate_id=c.id
                LEFT JOIN file_assets f ON f.id=rf.file_asset_id
                """;
        Integer total = jdbc.queryForObject("SELECT count(DISTINCT c.id) " + joins + where, Integer.class, params.toArray());
        List<CandidateSummary> items = jdbc.query("""
                SELECT c.id,c.company_id,c.workspace_id,c.display_name_masked,c.status,
                       rf.status AS parse_status,f.original_filename,pv.headline,pv.years_experience,
                       pv.highest_education,pv.skills::text,c.updated_at
                """ + joins + where + " ORDER BY c.updated_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> summary(rs), concat(params, safeSize, (safePage - 1) * safeSize));
        return new CandidateListResult(items, total == null ? 0 : total, safePage, safeSize);
    }

    public CandidateDetail get(UUID userId, UUID workspaceId, UUID candidateId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return detailScoped(workspaceId, candidateId);
    }

    public RevealedPii reveal(UUID userId, UUID workspaceId, UUID candidateId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        List<RevealedPii> rows = jdbc.query("""
                SELECT full_name_ciphertext,email_ciphertext,phone_ciphertext FROM candidates
                WHERE id=? AND workspace_id=? AND status<>'DELETED'
                """, (rs, n) -> new RevealedPii(pii.decrypt(rs.getString(1)), pii.decrypt(rs.getString(2)),
                pii.decrypt(rs.getString(3))), candidateId, workspaceId);
        if (rows.isEmpty()) throw notFound();
        audit(userId, scope, "CANDIDATE_PII_REVEALED", candidateId);
        return rows.getFirst();
    }

    @Transactional
    public DownloadedResume download(UUID userId, UUID workspaceId, UUID candidateId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        List<FileRow> rows = jdbc.query("""
                SELECT f.object_key,f.original_filename,f.media_type FROM candidates c
                JOIN resume_files rf ON rf.candidate_id=c.id JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE c.id=? AND c.workspace_id=? AND c.status<>'DELETED' AND f.lifecycle_status='ACTIVE'
                """, (rs, n) -> new FileRow(rs.getString(1), rs.getString(2), rs.getString(3)), candidateId, workspaceId);
        if (rows.isEmpty()) throw notFound();
        FileRow file = rows.getFirst();
        byte[] content = storage.get(file.objectKey());
        audit(userId, scope, "RESUME_DOWNLOADED", candidateId);
        return new DownloadedResume(file.filename(), file.mediaType(), content);
    }

    @Transactional
    public CandidateDetail retryParse(UUID userId, UUID workspaceId, UUID candidateId, String scenario) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        CandidateDetail existing = detailScoped(workspaceId, candidateId);
        if ("INVALID_SCHEMA".equals(normalizeScenario(scenario))) {
            jdbc.update("UPDATE resume_files SET status='PARSE_FAILED',error_code='AI_SCHEMA_INVALID',updated_at=? WHERE id=? AND workspace_id=?",
                    timestamp(Instant.now()), existing.resumeFileId(), workspaceId);
            return detailScoped(workspaceId, candidateId);
        }
        FileRow file = fileRow(workspaceId, candidateId);
        ParsedResume parsed = parse(file.filename(), extractor.extract(storage.get(file.objectKey()), file.filename()));
        Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM resume_parse_versions WHERE candidate_id=?",
                Integer.class, candidateId);
        saveParseVersion(scope, candidateId, existing.resumeFileId(), version == null ? 1 : version, parsed, Instant.now());
        jdbc.update("UPDATE resume_files SET status='PARSED',error_code=NULL,updated_at=? WHERE id=?",
                timestamp(Instant.now()), existing.resumeFileId());
        audit(userId, scope, "RESUME_PARSE_RETRIED", candidateId);
        return detailScoped(workspaceId, candidateId);
    }

    @Transactional
    public void delete(UUID userId, UUID workspaceId, UUID candidateId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        FileRow file = fileRow(workspaceId, candidateId);
        jdbc.update("UPDATE candidates SET status='DELETED',updated_at=? WHERE id=? AND workspace_id=?",
                timestamp(Instant.now()), candidateId, workspaceId);
        jdbc.update("UPDATE file_assets SET lifecycle_status='DELETING' WHERE object_key=? AND workspace_id=?",
                file.objectKey(), workspaceId);
        storage.remove(file.objectKey());
        jdbc.update("UPDATE file_assets SET lifecycle_status='DELETED' WHERE object_key=? AND workspace_id=?",
                file.objectKey(), workspaceId);
        audit(userId, scope, "CANDIDATE_DELETED", candidateId);
    }

    private void saveParseVersion(WorkspaceScope scope, UUID candidateId, UUID resumeFileId, int version,
                                  ParsedResume parsed, Instant now) {
        UUID parseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO resume_parse_versions
                (id,company_id,workspace_id,candidate_id,resume_file_id,version_number,schema_version,status,
                 headline,years_experience,highest_education,skills,work_experience,education_experience,
                 summary,warnings,raw_text,created_at)
                VALUES (?,?,?,?,?,?,'RESUME_V1','CONFIRMED',?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb,?,?)
                """, parseId, scope.companyId(), scope.workspaceId(), candidateId, resumeFileId, version,
                parsed.headline(), parsed.yearsExperience(), parsed.education(), json(parsed.skills()),
                json(parsed.workExperience()), json(parsed.educationExperience()), parsed.summary(),
                json(parsed.warnings()), parsed.rawText(), timestamp(now));
        jdbc.update("UPDATE candidates SET current_parse_version_id=?,updated_at=? WHERE id=? AND workspace_id=?",
                parseId, timestamp(now), candidateId, scope.workspaceId());
    }

    private CandidateDetail detailScoped(UUID workspaceId, UUID candidateId) {
        List<CandidateDetail> rows = jdbc.query("""
                SELECT c.id,c.company_id,c.workspace_id,c.display_name_masked,c.status,c.current_parse_version_id,
                       rf.id AS resume_file_id,rf.status AS parse_status,rf.error_code,
                       f.original_filename,f.media_type,f.size_bytes,
                       pv.version_number,pv.headline,pv.years_experience,pv.highest_education,pv.skills::text,
                       pv.work_experience::text,pv.education_experience::text,pv.summary,pv.warnings::text,
                       c.created_at,c.updated_at
                FROM candidates c JOIN resume_files rf ON rf.candidate_id=c.id
                JOIN file_assets f ON f.id=rf.file_asset_id
                LEFT JOIN resume_parse_versions pv ON pv.id=c.current_parse_version_id
                WHERE c.id=? AND c.workspace_id=? AND c.status<>'DELETED'
                """, (rs, n) -> new CandidateDetail(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("display_name_masked"), rs.getString("status"),
                rs.getObject("current_parse_version_id", UUID.class), rs.getObject("resume_file_id", UUID.class),
                rs.getString("parse_status"), rs.getString("error_code"), rs.getString("original_filename"),
                rs.getString("media_type"), rs.getLong("size_bytes"), rs.getInt("version_number"),
                rs.getString("headline"), rs.getInt("years_experience"), rs.getString("highest_education"),
                strings(rs.getString("skills")), strings(rs.getString("work_experience")),
                strings(rs.getString("education_experience")), rs.getString("summary"),
                strings(rs.getString("warnings")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), candidateId, workspaceId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private FileRow fileRow(UUID workspaceId, UUID candidateId) {
        List<FileRow> rows = jdbc.query("""
                SELECT f.object_key,f.original_filename,f.media_type FROM candidates c
                JOIN resume_files rf ON rf.candidate_id=c.id JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE c.id=? AND c.workspace_id=? AND c.status<>'DELETED'
                """, (rs, n) -> new FileRow(rs.getString(1), rs.getString(2), rs.getString(3)), candidateId, workspaceId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private ParsedResume parse(String filename, String rawText) {
        String name = filename.replaceFirst("(?i)\\.(pdf|docx)$", "")
                .replaceAll("(?i)(resume|cv|简历|候选人)", "").replaceAll("[_-]+", " ").trim();
        if (name.isBlank()) name = "候选人";
        name = name.substring(0, Math.min(name.length(), 50));
        String searchable = (rawText + " " + filename).toLowerCase(Locale.ROOT);
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        for (String skill : KNOWN_SKILLS) if (searchable.contains(skill.toLowerCase(Locale.ROOT))) skills.add(skill);
        if (skills.isEmpty()) skills.addAll(List.of("沟通协作", "问题解决"));
        int years = 0;
        Matcher yearMatcher = YEARS.matcher(rawText);
        while (yearMatcher.find()) years = Math.max(years, Integer.parseInt(yearMatcher.group(1)));
        String education = rawText.contains("硕士") ? "硕士" : rawText.contains("博士") ? "博士" :
                rawText.contains("本科") ? "本科" : "待确认";
        String email = match(EMAIL, rawText);
        String phone = match(PHONE, rawText);
        String skillText = String.join("、", skills);
        String headline = (years > 0 ? years + "年经验 · " : "") + skillText;
        String summary = "Mock 解析结果：候选人具备 " + skillText + " 等相关能力，详细经历需要招聘人员结合原简历复核。";
        List<String> warnings = rawText.isBlank() ? List.of("原文件文本提取有限，请对照原简历复核") : List.of();
        return new ParsedResume(name, email, phone, headline, years, education, new ArrayList<>(skills),
                List.of("工作经历已提取，待人工复核"), List.of(education + "教育经历"), summary, warnings, rawText);
    }

    private byte[] validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) throw validation("请选择简历文件");
        if (file.getSize() > maxFileSize) throw validation("简历文件不能超过10MB");
        String filename = safeFilename(file.getOriginalFilename());
        try {
            byte[] bytes = file.getBytes();
            boolean pdf = filename.toLowerCase(Locale.ROOT).endsWith(".pdf") && bytes.length >= 4
                    && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
            boolean docx = filename.toLowerCase(Locale.ROOT).endsWith(".docx") && bytes.length >= 4
                    && bytes[0] == 'P' && bytes[1] == 'K';
            if (!pdf && !docx) throw validation("仅支持内容有效的 PDF 或 DOCX 文件");
            return bytes;
        } catch (java.io.IOException exception) {
            throw validation("读取简历文件失败");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new ApiException("SERIALIZATION_FAILED", "保存解析结果失败", HttpStatus.INTERNAL_SERVER_ERROR); }
    }

    private List<String> strings(String json) {
        if (json == null) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { return List.of(); }
    }

    private void audit(UUID actor, WorkspaceScope scope, String action, UUID resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,company_id,workspace_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,?,'CANDIDATE',?,?)
                """, UUID.randomUUID(), actor, scope.companyId(), scope.workspaceId(), action,
                resourceId.toString(), timestamp(Instant.now()));
    }

    private static CandidateSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CandidateSummary(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getString("display_name_masked"), rs.getString("status"),
                rs.getString("parse_status"), rs.getString("original_filename"), rs.getString("headline"),
                rs.getInt("years_experience"), rs.getString("highest_education"), stringsStatic(rs.getString("skills")),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static List<String> stringsStatic(String json) {
        if (json == null || json.length() < 2) return List.of();
        String body = json.substring(1, json.length() - 1).trim();
        if (body.isBlank()) return List.of();
        return java.util.Arrays.stream(body.split(",")).map(item -> item.trim().replaceAll("^\"|\"$", ""))
                .filter(item -> !item.isBlank()).toList();
    }

    private static String normalizeScenario(String scenario) {
        String value = scenario == null || scenario.isBlank() ? "NORMAL" : scenario.toUpperCase(Locale.ROOT);
        if (!List.of("NORMAL", "INVALID_SCHEMA").contains(value)) throw validation("无效的解析场景");
        return value;
    }

    private static String safeFilename(String filename) {
        String value = filename == null ? "resume" : filename.replace("\\", "/");
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\r\\n]", "").trim();
        if (value.isBlank() || value.length() > 255) throw validation("简历文件名无效");
        return value;
    }

    private static String mediaType(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".pdf") ? "application/pdf" :
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    private static String mask(String name) {
        if (name.length() == 1) return name + "**";
        return name.substring(0, 1) + "**";
    }

    private static String match(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group() : "";
    }

    private static Object[] concat(List<Object> params, Object... extra) {
        Object[] values = new Object[params.size() + extra.length];
        for (int i = 0; i < params.size(); i++) values[i] = params.get(i);
        for (int i = 0; i < extra.length; i++) values[params.size() + i] = extra[i];
        return values;
    }

    private static ApiException validation(String message) {
        return new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
    }

    private static ApiException notFound() {
        return new ApiException("CANDIDATE_NOT_FOUND", "候选人不存在", HttpStatus.NOT_FOUND);
    }

    private record ParsedResume(String name, String email, String phone, String headline, int yearsExperience,
                                String education, List<String> skills, List<String> workExperience,
                                List<String> educationExperience, String summary, List<String> warnings,
                                String rawText) { }
    private record FileRow(String objectKey, String filename, String mediaType) { }

    public record CandidateSummary(UUID id, UUID companyId, UUID workspaceId, String displayNameMasked,
                                   String status, String parseStatus, String originalFilename, String headline,
                                   int yearsExperience, String highestEducation, List<String> skills,
                                   Instant updatedAt) { }
    public record CandidateDetail(UUID id, UUID companyId, UUID workspaceId, String displayNameMasked,
                                  String status, UUID currentParseVersionId, UUID resumeFileId, String parseStatus,
                                  String errorCode, String originalFilename, String mediaType, long sizeBytes,
                                  int parseVersion, String headline, int yearsExperience, String highestEducation,
                                  List<String> skills, List<String> workExperience, List<String> educationExperience,
                                  String summary, List<String> warnings, Instant createdAt, Instant updatedAt) { }
    public record CandidateListResult(List<CandidateSummary> items, int total, int page, int pageSize) { }
    public record RevealedPii(String fullName, String email, String phone) { }
    public record DownloadedResume(String filename, String mediaType, byte[] content) { }
}
