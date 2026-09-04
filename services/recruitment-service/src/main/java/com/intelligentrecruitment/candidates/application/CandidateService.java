package com.intelligentrecruitment.candidates.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.candidates.infrastructure.ResumeObjectStorage;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService.WorkspaceScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class CandidateService {

    private static final Pattern AI_NAME = Pattern.compile("(?m)^- 姓名：(.+)$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WorkspaceAccessService workspaceAccess;
    private final ResumeObjectStorage storage;
    private final ResumeTextExtractor extractor;
    private final PiiCipher pii;
    private final AiPlatformClient aiPlatform;
    private final long maxFileSize;

    public CandidateService(JdbcTemplate jdbc, ObjectMapper objectMapper, WorkspaceAccessService workspaceAccess,
                            ResumeObjectStorage storage, ResumeTextExtractor extractor, PiiCipher pii, AiPlatformClient aiPlatform,
                            @Value("${app.storage.max-file-size-bytes:10485760}") long maxFileSize) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workspaceAccess = workspaceAccess;
        this.storage = storage;
        this.extractor = extractor;
        this.pii = pii;
        this.aiPlatform = aiPlatform;
        this.maxFileSize = maxFileSize;
    }

    @Transactional
    public CandidateDetail upload(UUID userId, UUID workspaceId, MultipartFile file) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        byte[] bytes = validateAndRead(file);
        String hash = SecurityHashes.sha256(bytes);
        List<UUID> duplicate = jdbc.query("""
                SELECT c.id FROM candidates c JOIN resume_files rf ON rf.candidate_id=c.id
                JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE c.workspace_id=? AND f.sha256=? AND c.status<>'DELETED'
                """, (rs, n) -> rs.getObject(1, UUID.class), workspaceId, hash);
        if (!duplicate.isEmpty()) return detailScoped(workspaceId, duplicate.getFirst());

        AssetReference existingAsset = activeAsset(workspaceId, hash);
        // 活跃资产可能来自简历源文件；优先复用，避免破坏同一文件资产的去重关系。
        // 仅在没有活跃资产时，释放已删除候选人遗留的哈希唯一键。
        if (existingAsset == null) releaseHashSlot(workspaceId, hash);
        UUID assetId = existingAsset == null ? UUID.randomUUID() : existingAsset.id();
        UUID candidateId = UUID.randomUUID();
        UUID resumeFileId = UUID.randomUUID();
        String filename = safeFilename(file.getOriginalFilename());
        String objectKey = existingAsset == null ? workspaceId + "/" + assetId : existingAsset.objectKey();
        String mediaType = mediaType(filename);
        boolean createdAsset = existingAsset == null;
        if (createdAsset) storage.put(objectKey, bytes, mediaType);
        try {
            return persistUpload(userId, scope, assetId, candidateId, resumeFileId, objectKey, filename,
                    mediaType, bytes, hash, createdAsset);
        } catch (DuplicateKeyException duplicateKey) {
            if (!createdAsset) throw duplicateKey;
            releaseHashSlot(workspaceId, hash);
            return persistUpload(userId, scope, assetId, candidateId, resumeFileId, objectKey, filename,
                    mediaType, bytes, hash, true);
        } catch (RuntimeException exception) {
            if (createdAsset) {
                try { storage.remove(objectKey); } catch (RuntimeException ignored) { }
            }
            throw exception;
        }
    }

    protected CandidateDetail persistUpload(UUID userId, WorkspaceScope scope, UUID assetId, UUID candidateId,
                                            UUID resumeFileId, String objectKey, String filename, String mediaType,
                                            byte[] bytes, String hash, boolean createAsset) {
        Instant now = Instant.now();
        String rawText = extractor.extract(bytes, filename);
        String provisionalName = filenameDisplayName(filename);
        if (createAsset) {
            jdbc.update("""
                    INSERT INTO file_assets
                    (id,company_id,workspace_id,object_key,original_filename,media_type,size_bytes,sha256,
                     scan_status,lifecycle_status,created_by,created_at)
                    VALUES (?,?,?,?,?,?,?,?, 'CLEAN','ACTIVE',?,?)
                    """, assetId, scope.companyId(), scope.workspaceId(), objectKey, pii.encrypt(filename), mediaType, bytes.length,
                    hash, userId, timestamp(now));
        }
        jdbc.update("""
                INSERT INTO candidates
                (id,company_id,workspace_id,display_name_masked,full_name_ciphertext,email_ciphertext,
                 phone_ciphertext,full_name_search_hash,phone_search_hash,status,created_by,created_at,updated_at,profile,search_text)
                VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?::jsonb,?)
                """, candidateId, scope.companyId(), scope.workspaceId(), mask(provisionalName), pii.encrypt(provisionalName),
                pii.encrypt(""), pii.encrypt(""), pii.searchToken(provisionalName), pii.searchToken(""),
                userId, timestamp(now), timestamp(now), json(Map.of("source", "简历上传", "tags", List.of())), provisionalName);
        jdbc.update("""
                INSERT INTO resume_files
                (id,company_id,workspace_id,candidate_id,file_asset_id,status,error_code,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?, ?,?,?)
                """, resumeFileId, scope.companyId(), scope.workspaceId(), candidateId, assetId, "PROCESSING",
                null, userId, timestamp(now), timestamp(now));
        parseAndSave(userId, scope, candidateId, resumeFileId, filename, rawText, 1);
        audit(userId, scope, "RESUME_UPLOADED", candidateId);
        return detailScoped(scope.workspaceId(), candidateId);
    }

    /**
     * file_assets deduplicates binary files within a workspace. A resume may first
     * have been uploaded as a task source file, so it may not yet have a candidate
     * record. Reuse that active asset instead of attempting a second insert.
     */
    private AssetReference activeAsset(UUID workspaceId, String sha256) {
        List<AssetReference> rows = jdbc.query("""
                SELECT id,object_key FROM file_assets
                WHERE workspace_id=? AND sha256=? AND lifecycle_status='ACTIVE'
                """, (rs, n) -> new AssetReference(rs.getObject(1, UUID.class), rs.getString(2)), workspaceId, sha256);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public CandidateStats stats(UUID userId, UUID workspaceId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        StatPoint total = metric(
                countTotal(workspaceId, null),
                countTotal(workspaceId, "PREV_MONTH_END"));
        StatPoint active = metric(
                countActive(workspaceId, false),
                countActive(workspaceId, true));
        StatPoint highMatch = metric(
                countHighMatch(workspaceId, false),
                countHighMatch(workspaceId, true));
        StatPoint dormant = metric(
                countDormant(workspaceId, false),
                countDormant(workspaceId, true));
        StatPoint inPool = metric(
                countInPool(workspaceId, null),
                countInPool(workspaceId, "PREV_MONTH_END"));
        return new CandidateStats(total, active, highMatch, dormant, inPool, 80);
    }

    public CandidateListResult list(UUID userId, UUID workspaceId, CandidateListQuery query) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        int safePage = Math.max(1, query.page());
        // 筛选工作台需要一次加载最多 200 位已解析候选人；保持旧接口容量，
        // 同时使用远端新增的统一查询条件。
        int safeSize = Math.min(200, Math.max(1, query.pageSize()));
        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
        StringBuilder where = new StringBuilder(" WHERE c.workspace_id=? AND c.status<>'DELETED'");
        appendSearch(where, params, query.search());
        if (query.status() != null && !query.status().isBlank()) {
            where.append(" AND rf.status=?");
            params.add(query.status().toUpperCase(Locale.ROOT));
        }
        String normalizedSegment = query.segment() == null ? "" : query.segment().trim().toUpperCase(Locale.ROOT);
        int scoreThreshold = query.minMatchScore() == null ? 80 : Math.max(0, Math.min(100, query.minMatchScore()));
        if ("ACTIVE_TALENT".equals(normalizedSegment)) {
            where.append(" AND c.updated_at >= (CURRENT_TIMESTAMP - INTERVAL '30 days')");
        } else if ("HIGH_MATCH".equals(normalizedSegment)) {
            where.append("""
                     AND EXISTS (
                       SELECT 1 FROM screening_run_items sri
                       JOIN screening_results sr ON sr.run_item_id = sri.id
                       WHERE sri.candidate_id = c.id AND sri.workspace_id = c.workspace_id
                         AND sr.score >= ?
                     )
                    """);
            params.add(scoreThreshold);
        } else if ("DORMANT".equals(normalizedSegment)) {
            where.append(" AND c.updated_at < (CURRENT_TIMESTAMP - INTERVAL '90 days')");
        } else if ("IN_POOL".equals(normalizedSegment)) {
            where.append(" AND (rf.status='PARSED' OR c.profile->>'source' IS NOT NULL)");
        }
        if (!"HIGH_MATCH".equals(normalizedSegment) && query.minMatchScore() != null) {
            where.append("""
                     AND EXISTS (
                       SELECT 1 FROM screening_run_items sri
                       JOIN screening_results sr ON sr.run_item_id = sri.id
                       WHERE sri.candidate_id = c.id AND sri.workspace_id = c.workspace_id
                         AND sr.score >= ?
                     )
                    """);
            params.add(scoreThreshold);
        }
        if (query.industry() != null && !query.industry().isBlank()) {
            where.append(" AND c.profile->>'industry'=?");
            params.add(query.industry().trim());
        }
        if (query.city() != null && !query.city().isBlank()) {
            where.append(" AND (c.profile->>'city'=? OR c.profile->>'province'=? OR c.profile->>'district'=?)");
            String city = query.city().trim();
            params.add(city); params.add(city); params.add(city);
        }
        if (query.tags() != null && !query.tags().isBlank()) {
            String[] tags = query.tags().split("[,，]");
            List<String> cleaned = new ArrayList<>();
            for (String tag : tags) {
                String value = tag.trim();
                if (!value.isBlank()) cleaned.add(value);
            }
            if (!cleaned.isEmpty()) {
                where.append(" AND (");
                for (int i = 0; i < cleaned.size(); i++) {
                    if (i > 0) where.append(" OR ");
                    where.append(" jsonb_exists(c.profile->'tags', ?)");
                    params.add(cleaned.get(i));
                }
                where.append(")");
            }
        }
        if (query.education() != null && !query.education().isBlank()) {
            where.append(" AND (c.profile->>'highestEducation'=? OR pv.highest_education ILIKE ?)");
            params.add(query.education().trim());
            params.add("%" + query.education().trim() + "%");
        }
        if (query.source() != null && !query.source().isBlank()) {
            where.append(" AND c.profile->>'source'=?");
            params.add(query.source().trim());
        }
        if (query.activity() != null && !query.activity().isBlank()) {
            where.append(" AND c.profile->>'activityLevel'=?");
            params.add(query.activity().trim());
        }
        if (query.talentStatus() != null && !query.talentStatus().isBlank()) {
            where.append(" AND c.profile->>'talentStatus'=?");
            params.add(query.talentStatus().trim());
        }
        if (query.yearsMin() != null) {
            where.append(" AND COALESCE(NULLIF(c.profile->>'yearsExperience','')::int, pv.years_experience, 0) >= ?");
            params.add(query.yearsMin());
        }
        if (query.yearsMax() != null) {
            where.append(" AND COALESCE(NULLIF(c.profile->>'yearsExperience','')::int, pv.years_experience, 0) <= ?");
            params.add(query.yearsMax());
        }
        if (query.createdFrom() != null && !query.createdFrom().isBlank()) {
            where.append(" AND c.created_at >= ?::timestamptz");
            params.add(query.createdFrom().trim());
        }
        if (query.createdTo() != null && !query.createdTo().isBlank()) {
            where.append(" AND c.created_at <= (?::date + INTERVAL '1 day')");
            params.add(query.createdTo().trim());
        }
        if (query.minMatchScore() != null && !"HIGH_MATCH".equals(normalizedSegment)) {
            where.append(" AND ms.match_score >= ?");
            params.add(scoreThreshold);
        }
        String joins = """
                FROM candidates c LEFT JOIN resume_parse_versions pv ON pv.id=c.current_parse_version_id
                LEFT JOIN resume_files rf ON rf.candidate_id=c.id
                LEFT JOIN file_assets f ON f.id=rf.file_asset_id
                LEFT JOIN LATERAL (
                    SELECT sr.score AS match_score, j.title AS matched_job_title
                    FROM screening_run_items sri
                    JOIN screening_results sr ON sr.run_item_id = sri.id
                    JOIN screening_runs run ON run.id = sri.run_id
                    JOIN jobs j ON j.id = run.job_id
                    WHERE sri.candidate_id = c.id AND sri.workspace_id = c.workspace_id
                    ORDER BY sr.score DESC, sr.created_at DESC
                    LIMIT 1
                ) ms ON TRUE
                """;
        Integer total = jdbc.queryForObject("SELECT count(DISTINCT c.id) " + joins + where, Integer.class, params.toArray());
        List<CandidateSummary> items = jdbc.query("""
                SELECT c.id,c.company_id,c.workspace_id,c.full_name_ciphertext,c.phone_ciphertext,c.email_ciphertext,c.status,
                       COALESCE(rf.status, 'PARSED') AS parse_status,COALESCE(f.original_filename, '手动录入') AS original_filename,
                       COALESCE(pv.headline, CONCAT_WS(' | ', c.profile->>'currentTitle', c.profile->>'currentCompany')) AS headline,
                       COALESCE(NULLIF(c.profile->>'yearsExperience','')::int, pv.years_experience, 0) AS years_experience,
                       COALESCE(c.profile->>'highestEducation', pv.highest_education, '') AS highest_education,
                       COALESCE(c.profile->'skills', pv.skills, '[]'::jsonb)::text AS skills,
                       c.created_at,c.updated_at,ms.match_score,ms.matched_job_title,c.profile::text AS profile_json
                """ + joins + where + " ORDER BY c.updated_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> summary(rs), concat(params, safeSize, (safePage - 1) * safeSize));
        return new CandidateListResult(items, total == null ? 0 : total, safePage, safeSize);
    }

    public CandidateListResult list(UUID userId, UUID workspaceId, String search, String status, String segment,
                                    Integer minMatchScore, int page, int pageSize) {
        return list(userId, workspaceId, new CandidateListQuery(search, status, segment, minMatchScore,
                null, null, null, null, null, null, null, null, null, null, null, page, pageSize));
    }

    public CandidateListResult list(UUID userId, UUID workspaceId, String search, String status, int page, int pageSize) {
        return list(userId, workspaceId, search, status, null, null, page, pageSize);
    }

    @Transactional
    public CandidateDetail createManual(UUID userId, UUID workspaceId, ManualTalentInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        if (input == null || isBlank(input.fullName())) throw validation("姓名不能为空");
        Instant now = Instant.now();
        UUID candidateId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID resumeFileId = UUID.randomUUID();
        String name = input.fullName().trim();
        String phone = nullable(input.phone());
        String email = nullable(input.email());
        List<String> skills = mergeSkills(input);
        String headline = joinNonBlank(" | ", nullable(input.currentTitle()), nullable(input.currentCompany()));
        if (headline.isBlank()) headline = skills.isEmpty() ? "手动录入人才" : String.join("、", skills);
        int years = parseYears(input.yearsExperience());
        Map<String, Object> profile = profileMap(input, skills, years);
        String searchText = buildSearchText(candidateId, profile, skills, headline);
        String filename = "manual-" + candidateId + ".txt";
        String objectKey = workspaceId + "/" + assetId;
        byte[] bytes = ("手动录入人才档案\n" + searchText).getBytes(StandardCharsets.UTF_8);
        storage.put(objectKey, bytes, "text/plain");
        try {
            jdbc.update("""
                    INSERT INTO file_assets
                    (id,company_id,workspace_id,object_key,original_filename,media_type,size_bytes,sha256,
                     scan_status,lifecycle_status,created_by,created_at)
                    VALUES (?,?,?,?,?,?,?,?,'CLEAN','ACTIVE',?,?)
                    """, assetId, scope.companyId(), scope.workspaceId(), objectKey, pii.encrypt(filename), "text/plain",
                    bytes.length, SecurityHashes.sha256(bytes), userId, timestamp(now));
            jdbc.update("""
                    INSERT INTO candidates
                    (id,company_id,workspace_id,display_name_masked,full_name_ciphertext,email_ciphertext,
                     phone_ciphertext,full_name_search_hash,phone_search_hash,status,created_by,created_at,updated_at,profile,search_text)
                    VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?::jsonb,?)
                    """, candidateId, scope.companyId(), scope.workspaceId(), mask(name), pii.encrypt(name),
                    pii.encrypt(email), pii.encrypt(phone), pii.searchToken(name), pii.searchToken(phone), userId, timestamp(now), timestamp(now),
                    json(profile), searchText);
            jdbc.update("""
                    INSERT INTO resume_files
                    (id,company_id,workspace_id,candidate_id,file_asset_id,status,error_code,created_by,created_at,updated_at)
                    VALUES (?,?,?,?,?,'PARSED',NULL,?,?,?)
                    """, resumeFileId, scope.companyId(), scope.workspaceId(), candidateId, assetId, userId,
                    timestamp(now), timestamp(now));
            ParsedResume parsed = new ParsedResume(name, email == null ? "" : email, phone == null ? "" : phone,
                    headline, years, nullable(input.highestEducation()).isBlank() ? "待确认" : input.highestEducation().trim(),
                    skills,
                    List.of(joinNonBlank(" / ", nullable(input.currentCompany()), nullable(input.currentTitle()))),
                    List.of(joinNonBlank(" · ", nullable(input.school()), nullable(input.major()), nullable(input.highestEducation()))),
                    "手动录入人才档案", List.of(), "手动录入人才档案");
            saveParseVersion(scope, candidateId, resumeFileId, 1, parsed, now);
            audit(userId, scope, "CANDIDATE_CREATED_MANUAL", candidateId);
            return detailScoped(scope.workspaceId(), candidateId);
        } catch (RuntimeException exception) {
            try { storage.remove(objectKey); } catch (RuntimeException ignored) { }
            throw exception;
        }
    }

    /**
     * AI 简历解析「发布到人才库」：基于招聘任务已上传的简历源文件资产创建候选人。
     * 复用已有 file_assets（不重复存储文件），并用任务已提取的简历文本走人才库解析入库；
     * 同一文件资产已关联候选人时幂等返回，避免重复入库。
     */
    @Transactional
    public CandidateDetail createFromResumeSource(UUID userId, UUID workspaceId, UUID assetId,
                                                  String filename, String extractedText) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        // 幂等：该简历资产已入库为候选人则直接返回已有候选人
        List<UUID> linked = jdbc.query("""
                SELECT c.id FROM candidates c
                JOIN resume_files rf ON rf.candidate_id=c.id
                WHERE rf.file_asset_id=? AND c.workspace_id=? AND c.status<>'DELETED'
                """, (rs, n) -> rs.getObject(1, UUID.class), assetId, workspaceId);
        if (!linked.isEmpty()) return detailScoped(workspaceId, linked.getFirst());
        // 资产必须存在且属于当前工作空间
        Integer assetCount = jdbc.queryForObject(
                "SELECT count(*) FROM file_assets WHERE id=? AND workspace_id=? AND lifecycle_status='ACTIVE'",
                Integer.class, assetId, workspaceId);
        if (assetCount == null || assetCount == 0) {
            throw new ApiException("FILE_ASSET_NOT_FOUND", "简历文件不存在或已失效，请重新上传", HttpStatus.NOT_FOUND);
        }
        UUID candidateId = UUID.randomUUID();
        UUID resumeFileId = UUID.randomUUID();
        Instant now = Instant.now();
        String safeName = filenameDisplayName(filename);
        // 先建候选人（姓名暂用文件名占位，人才库解析完成后会回填真实姓名）
        jdbc.update("""
                INSERT INTO candidates
                (id,company_id,workspace_id,display_name_masked,full_name_ciphertext,email_ciphertext,
                 phone_ciphertext,full_name_search_hash,phone_search_hash,status,created_by,created_at,updated_at,profile,search_text)
                VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?::jsonb,?)
                """, candidateId, scope.companyId(), scope.workspaceId(), mask(safeName), pii.encrypt(safeName),
                pii.encrypt(""), pii.encrypt(""), pii.searchToken(safeName), pii.searchToken(""),
                userId, timestamp(now), timestamp(now),
                json(Map.of("source", "AI简历解析", "tags", List.of())), safeName);
        jdbc.update("""
                INSERT INTO resume_files
                (id,company_id,workspace_id,candidate_id,file_asset_id,status,error_code,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,'PROCESSING',NULL,?,?,?)
                """, resumeFileId, scope.companyId(), scope.workspaceId(), candidateId, assetId, userId,
                timestamp(now), timestamp(now));
        // 用任务已提取的简历文本走人才库解析入库（内部失败有兜底，不影响候选人创建）
        String rawText = extractedText == null || extractedText.isBlank() ? safeName : extractedText;
        parseAndSave(userId, scope, candidateId, resumeFileId, filename, rawText, 1);
        audit(userId, scope, "CANDIDATE_CREATED_FROM_RESUME_PARSE", candidateId);
        return detailScoped(workspaceId, candidateId);
    }

    private void appendSearch(StringBuilder where, List<Object> params, String search) {
        if (search == null || search.isBlank()) return;
        List<String> tokens = tokenizeSearch(search);
        for (String token : tokens) {
            if (token.isBlank()) continue;
            if (looksLikeUuid(token)) {
                where.append(" AND c.id=?");
                params.add(UUID.fromString(token));
                continue;
            }
            String like = "%" + token + "%";
            String piiToken = pii.searchToken(token);
            where.append("""
                     AND (
                       c.id::text ILIKE ?
                       OR c.search_text ILIKE ?
                       OR c.profile::text ILIKE ?
                       OR pv.headline ILIKE ?
                       OR pv.skills::text ILIKE ?
                       OR pv.work_experience::text ILIKE ?
                       OR pv.education_experience::text ILIKE ?
                       OR pv.summary ILIKE ?
                       OR pv.highest_education ILIKE ?
                       OR c.full_name_search_hash=?
                       OR c.phone_search_hash=?
                     )
                    """);
            for (int i = 0; i < 9; i++) params.add(like);
            params.add(piiToken);
            params.add(piiToken);
        }
    }

    /** Split by whitespace; keep quoted phrases as exact multi-word tokens. */
    private static List<String> tokenizeSearch(String search) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"|(\\S+)").matcher(search.trim());
        while (matcher.find()) {
            String quoted = matcher.group(1);
            String plain = matcher.group(2);
            tokens.add(quoted != null ? quoted.trim() : plain.trim());
        }
        return tokens;
    }


    private int countTotal(UUID workspaceId, String mode) {
        if ("PREV_MONTH_END".equals(mode)) {
            return value(jdbc.queryForObject("""
                    SELECT count(*) FROM candidates
                    WHERE workspace_id=? AND created_at < date_trunc('month', CURRENT_TIMESTAMP)
                      AND (status<>'DELETED' OR updated_at >= date_trunc('month', CURRENT_TIMESTAMP))
                    """, Integer.class, workspaceId));
        }
        return value(jdbc.queryForObject(
                "SELECT count(*) FROM candidates WHERE workspace_id=? AND status<>'DELETED'",
                Integer.class, workspaceId));
    }

    private int countActive(UUID workspaceId, boolean previousWindow) {
        if (previousWindow) {
            return value(jdbc.queryForObject("""
                    SELECT count(*) FROM candidates
                    WHERE workspace_id=? AND status<>'DELETED'
                      AND updated_at >= (date_trunc('month', CURRENT_TIMESTAMP) - INTERVAL '30 days')
                      AND updated_at < date_trunc('month', CURRENT_TIMESTAMP)
                    """, Integer.class, workspaceId));
        }
        return value(jdbc.queryForObject("""
                SELECT count(*) FROM candidates
                WHERE workspace_id=? AND status<>'DELETED'
                  AND updated_at >= (CURRENT_TIMESTAMP - INTERVAL '30 days')
                """, Integer.class, workspaceId));
    }

    private int countHighMatch(UUID workspaceId, boolean previousMonth) {
        if (previousMonth) {
            return value(jdbc.queryForObject("""
                    SELECT count(DISTINCT sri.candidate_id)
                    FROM screening_run_items sri
                    JOIN screening_results sr ON sr.run_item_id = sri.id
                    JOIN candidates c ON c.id = sri.candidate_id
                    WHERE sri.workspace_id=? AND c.status<>'DELETED'
                      AND sr.score >= 80
                      AND sr.created_at < date_trunc('month', CURRENT_TIMESTAMP)
                    """, Integer.class, workspaceId));
        }
        return value(jdbc.queryForObject("""
                SELECT count(DISTINCT sri.candidate_id)
                FROM screening_run_items sri
                JOIN screening_results sr ON sr.run_item_id = sri.id
                JOIN candidates c ON c.id = sri.candidate_id
                WHERE sri.workspace_id=? AND c.status<>'DELETED' AND sr.score >= 80
                """, Integer.class, workspaceId));
    }

    private int countDormant(UUID workspaceId, boolean previousMonth) {
        if (previousMonth) {
            return value(jdbc.queryForObject("""
                    SELECT count(*) FROM candidates
                    WHERE workspace_id=? AND status<>'DELETED'
                      AND created_at < date_trunc('month', CURRENT_TIMESTAMP)
                      AND updated_at < (date_trunc('month', CURRENT_TIMESTAMP) - INTERVAL '90 days')
                    """, Integer.class, workspaceId));
        }
        return value(jdbc.queryForObject("""
                SELECT count(*) FROM candidates
                WHERE workspace_id=? AND status<>'DELETED'
                  AND updated_at < (CURRENT_TIMESTAMP - INTERVAL '90 days')
                """, Integer.class, workspaceId));
    }

    private int countInPool(UUID workspaceId, String mode) {
        if ("PREV_MONTH_END".equals(mode)) {
            return value(jdbc.queryForObject("""
                    SELECT count(DISTINCT c.id)
                    FROM candidates c
                    JOIN resume_files rf ON rf.candidate_id = c.id
                    WHERE c.workspace_id=? AND c.status<>'DELETED' AND rf.status='PARSED'
                      AND c.created_at < date_trunc('month', CURRENT_TIMESTAMP)
                    """, Integer.class, workspaceId));
        }
        return value(jdbc.queryForObject("""
                SELECT count(DISTINCT c.id)
                FROM candidates c
                JOIN resume_files rf ON rf.candidate_id = c.id
                WHERE c.workspace_id=? AND c.status<>'DELETED' AND rf.status='PARSED'
                """, Integer.class, workspaceId));
    }

    private static StatPoint metric(int current, int previous) {
        double changePercent = previous == 0
                ? (current == 0 ? 0d : 100d)
                : ((current - previous) * 1000d / previous) / 10d;
        return new StatPoint(current, previous, changePercent);
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
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
        return new DownloadedResume(pii.decryptIfEncrypted(file.filename()), file.mediaType(), content);
    }

    @Transactional
    public CandidateDetail retryParse(UUID userId, UUID workspaceId, UUID candidateId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        CandidateDetail existing = detailScoped(workspaceId, candidateId);
        FileRow file = fileRow(workspaceId, candidateId);
        String filename = pii.decryptIfEncrypted(file.filename());
        jdbc.update("UPDATE resume_files SET status='PROCESSING',error_code=NULL,updated_at=? WHERE id=? AND workspace_id=?",
                timestamp(Instant.now()), existing.resumeFileId(), workspaceId);
        Integer version = jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM resume_parse_versions WHERE candidate_id=?",
                Integer.class, candidateId);
        parseAndSave(userId, scope, candidateId, existing.resumeFileId(), filename,
                extractor.extract(storage.get(file.objectKey()), filename), version == null ? 1 : version);
        audit(userId, scope, "RESUME_PARSE_RETRIED", candidateId);
        return detailScoped(workspaceId, candidateId);
    }

    @Transactional
    public void delete(UUID userId, UUID workspaceId, UUID candidateId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        List<FileRow> files = jdbc.query("""
                SELECT f.object_key,f.original_filename,f.media_type FROM candidates c
                JOIN resume_files rf ON rf.candidate_id=c.id JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE c.id=? AND c.workspace_id=? AND c.status<>'DELETED'
                """, (rs, n) -> new FileRow(rs.getString(1), rs.getString(2), rs.getString(3)), candidateId, workspaceId);
        jdbc.update("UPDATE candidates SET status='DELETED',updated_at=? WHERE id=? AND workspace_id=?",
                timestamp(Instant.now()), candidateId, workspaceId);
        for (FileRow file : files) {
            jdbc.update("UPDATE file_assets SET lifecycle_status='DELETING' WHERE object_key=? AND workspace_id=?",
                    file.objectKey(), workspaceId);
            try { storage.remove(file.objectKey()); } catch (RuntimeException ignored) { }
            List<AssetHashRow> assets = jdbc.query("""
                    SELECT id, sha256 FROM file_assets WHERE object_key=? AND workspace_id=?
                    """, (rs, rowNum) -> new AssetHashRow(rs.getObject(1, UUID.class), rs.getString(2)),
                    file.objectKey(), workspaceId);
            for (AssetHashRow asset : assets) {
                jdbc.update("""
                        UPDATE file_assets
                        SET lifecycle_status='DELETED', sha256=?
                        WHERE id=? AND workspace_id=?
                        """, archivedSha256(asset.id(), asset.sha256()), asset.id(), workspaceId);
            }
        }
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
                json(parsed.warnings()), pii.encrypt(parsed.rawText()), timestamp(now));
        jdbc.update("UPDATE candidates SET current_parse_version_id=?,updated_at=? WHERE id=? AND workspace_id=?",
                parseId, timestamp(now), candidateId, scope.workspaceId());
    }

    private CandidateDetail detailScoped(UUID workspaceId, UUID candidateId) {
        List<CandidateDetail> rows = jdbc.query("""
                SELECT c.id,c.company_id,c.workspace_id,c.full_name_ciphertext,c.phone_ciphertext,c.email_ciphertext,c.status,c.current_parse_version_id,
                       rf.id AS resume_file_id,rf.status AS parse_status,rf.error_code,
                       f.original_filename,f.media_type,f.size_bytes,
                       COALESCE(NULLIF(c.profile->>'yearsExperience','')::int, pv.years_experience, 0) AS years_experience,
                       COALESCE(c.profile->>'highestEducation', pv.highest_education, '') AS highest_education,
                       COALESCE(c.profile->'skills', pv.skills, '[]'::jsonb)::text AS skills,
                       COALESCE(pv.headline, CONCAT_WS(' | ', c.profile->>'currentTitle', c.profile->>'currentCompany')) AS headline,
                       pv.version_number,pv.work_experience::text,pv.education_experience::text,pv.summary,pv.warnings::text,
                       c.created_at,c.updated_at,c.profile::text AS profile_json,
                       ms.match_score,ms.matched_job_title
                FROM candidates c JOIN resume_files rf ON rf.candidate_id=c.id
                JOIN file_assets f ON f.id=rf.file_asset_id
                LEFT JOIN resume_parse_versions pv ON pv.id=c.current_parse_version_id
                LEFT JOIN LATERAL (
                    SELECT sr.score AS match_score, j.title AS matched_job_title
                    FROM screening_run_items sri
                    JOIN screening_results sr ON sr.run_item_id = sri.id
                    JOIN screening_runs run ON run.id = sri.run_id
                    JOIN jobs j ON j.id = run.job_id
                    WHERE sri.candidate_id = c.id AND sri.workspace_id = c.workspace_id
                    ORDER BY sr.score DESC NULLS LAST, sri.created_at DESC
                    LIMIT 1
                ) ms ON TRUE
                WHERE c.id=? AND c.workspace_id=? AND c.status<>'DELETED'
                """, (rs, n) -> {
            Integer score = rs.getObject("match_score") == null ? null : rs.getInt("match_score");
            return new CandidateDetail(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("workspace_id", UUID.class),
                pii.decrypt(rs.getString("full_name_ciphertext")), pii.decrypt(rs.getString("phone_ciphertext")), pii.decrypt(rs.getString("email_ciphertext")), rs.getString("status"),
                rs.getObject("current_parse_version_id", UUID.class), rs.getObject("resume_file_id", UUID.class),
                rs.getString("parse_status"), rs.getString("error_code"), pii.decryptIfEncrypted(rs.getString("original_filename")),
                rs.getString("media_type"), rs.getLong("size_bytes"), rs.getInt("version_number"),
                rs.getString("headline"), rs.getInt("years_experience"), rs.getString("highest_education"),
                strings(rs.getString("skills")), strings(rs.getString("work_experience")),
                strings(rs.getString("education_experience")), rs.getString("summary"),
                strings(rs.getString("warnings")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), score, rs.getString("matched_job_title"),
                rs.getString("profile_json"));
        }, candidateId, workspaceId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    @Transactional
    public CandidateDetail updateTags(UUID userId, UUID workspaceId, UUID candidateId, List<String> tags) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        CandidateDetail existing = detailScoped(workspaceId, candidateId);
        Map<String, Object> profile = parseProfileMap(existing.profileJson());
        List<String> cleaned = tags == null ? List.of() : tags.stream()
                .filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().toList();
        profile.put("tags", cleaned);
        String searchText = buildSearchText(existing.id(),
                profile, existing.skills(), existing.headline());
        jdbc.update("UPDATE candidates SET profile=?::jsonb, search_text=?, updated_at=? WHERE id=? AND workspace_id=?",
                json(profile), searchText, timestamp(Instant.now()), candidateId, workspaceId);
        return detailScoped(workspaceId, candidateId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseProfileMap(String profileJson) {
        if (profileJson == null || profileJson.isBlank()) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(objectMapper.readValue(profileJson, new TypeReference<Map<String, Object>>() {}));
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
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

    private void parseAndSave(UUID userId, WorkspaceScope scope, UUID candidateId, UUID resumeFileId,
                              String filename, String rawText, int version) {
        ParsedResume parsed;
        try {
            parsed = parseWithDeepSeek(scope, userId, candidateId, filename, rawText);
        } catch (RuntimeException exception) {
            String code = exception instanceof ApiException api ? api.code() : "AI_PROVIDER_UNAVAILABLE";
            // 连简历原文都没有（如纯图片 PDF），无法兜底：标记失败，等待重新上传或解析
            if (rawText == null || rawText.isBlank()) {
                jdbc.update("UPDATE resume_files SET status='PARSE_FAILED',error_code=?,updated_at=? WHERE id=? AND workspace_id=?",
                        code, timestamp(Instant.now()), resumeFileId, scope.workspaceId());
                return;
            }
            // 降级兜底：AI 不可用/超时时，用本地已抽取的简历原文建立解析版本，
            // 保证候选人能进入人才库与简历筛选（筛选 AI 直接读取简历原文），结构化字段稍后可重新解析补全
            String safeName = filenameDisplayName(filename);
            parsed = new ParsedResume(safeName, "", "", "简历原文已入库，AI 结构化待完善", 0, "待确认",
                    List.of(), List.of(), List.of(), rawText,
                    List.of("AI 解析暂不可用（" + code + "），已保存简历原文；可稍后在人才库点击「重新解析」补全结构化信息"),
                    rawText);
        }
        Instant now = Instant.now();
        saveParseVersion(scope, candidateId, resumeFileId, version, parsed, now);
        Map<String, Object> profile = uploadProfile(parsed);
        jdbc.update("""
                UPDATE candidates SET display_name_masked=?,full_name_ciphertext=?,email_ciphertext=?,phone_ciphertext=?,
                full_name_search_hash=?,phone_search_hash=?,profile=?::jsonb,search_text=?,updated_at=?
                WHERE id=? AND workspace_id=?
                """, mask(parsed.name()), pii.encrypt(parsed.name()), pii.encrypt(parsed.email()), pii.encrypt(parsed.phone()),
                pii.searchToken(parsed.name()), pii.searchToken(parsed.phone()), json(profile),
                buildSearchText(candidateId, profile, parsed.skills(), parsed.headline()), timestamp(now),
                candidateId, scope.workspaceId());
        // 兜底与成功都视为已解析（有可用简历原文）；失败码清空，结构化完善程度通过解析版本 warnings 体现
        jdbc.update("UPDATE resume_files SET status='PARSED',error_code=NULL,updated_at=? WHERE id=? AND workspace_id=?",
                timestamp(now), resumeFileId, scope.workspaceId());
    }

    private ParsedResume parseWithDeepSeek(WorkspaceScope scope, UUID userId, UUID candidateId, String filename, String rawText) {
        String key = "candidate-resume-parse:" + candidateId + ":" + UUID.randomUUID();
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(), null, key, key, scope.workspaceId(),
                scope.companyId(), userId, candidateId, key, FlowCapability.RESUME_PARSING, key, List.of(), null,
                new ExecutionContext.DataHandling(true, "ephemeral", false), Instant.now());
        AiTask task = aiPlatform.startTask(new StartAiTaskCommand(scope.workspaceId().toString(),
                scope.companyId() == null ? null : scope.companyId().toString(),
                userId.toString(), candidateId.toString(), key, AiCapability.RESUME_PARSING,
                Map.of("resumes", List.of(Map.of("filename", filename, "text", rawText, "source", "candidate_library")),
                        "job", Map.of()), context));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            AiTask current = aiPlatform.getTask(task.aiTaskId());
            if (current.status() == AiTaskStatus.COMPLETED) {
                StructuredResult result = aiPlatform.getStructuredResult(task.aiTaskId());
                String markdown = String.valueOf(result.data().getOrDefault("markdown", "")).trim();
                if (markdown.isBlank()) throw new ApiException("AI_SCHEMA_INVALID", "DeepSeek 未返回有效简历解析结果", HttpStatus.BAD_GATEWAY);
                return parsedFromAi(filename, rawText, markdown, stringList(result.data().get("warnings")));
            }
            if (current.status() == AiTaskStatus.FAILED || current.status() == AiTaskStatus.CANCELLED) {
                throw new ApiException("AI_PROVIDER_UNAVAILABLE", "DeepSeek 简历解析失败", HttpStatus.BAD_GATEWAY);
            }
            try { Thread.sleep(100); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ApiException("AI_TIMEOUT", "DeepSeek 简历解析被中断", HttpStatus.GATEWAY_TIMEOUT);
            }
        }
        throw new ApiException("AI_TIMEOUT", "DeepSeek 简历解析超时，请重试", HttpStatus.GATEWAY_TIMEOUT);
    }

    private ParsedResume parsedFromAi(String filename, String rawText, String markdown, List<String> warnings) {
        String name = firstMatch(AI_NAME, markdown, filenameDisplayName(filename));
        List<String> skills = aiSkills(markdown);
        String headline = skills.isEmpty() ? "AI 已完成简历解析" : String.join("、", skills);
        return new ParsedResume(name, "", "", headline, 0, "待确认", skills, List.of(), List.of(), markdown,
                warnings == null ? List.of() : warnings, rawText);
    }

    private static String filenameDisplayName(String filename) {
        String value = filename.replaceFirst("(?i)\\.(pdf|docx|txt)$", "")
                .replaceAll("(?i)(resume|cv|简历|候选人)", "").replaceAll("[_-]+", " ").trim();
        return value.isBlank() ? "待 AI 识别" : value.substring(0, Math.min(value.length(), 50));
    }

    private static String firstMatch(Pattern pattern, String value, String fallback) {
        Matcher matcher = pattern.matcher(value);
        String result = matcher.find() ? matcher.group(1).trim() : fallback;
        return result.isBlank() || result.contains("待确认") ? fallback : result.substring(0, Math.min(result.length(), 50));
    }

    private static List<String> aiSkills(String markdown) {
        int section = markdown.indexOf("### 4. 核心技能标签");
        if (section < 0) return List.of();
        String body = markdown.substring(section + "### 4. 核心技能标签".length());
        int next = body.indexOf("###");
        if (next >= 0) body = body.substring(0, next);
        return java.util.Arrays.stream(body.replace("-", "").trim().split("[、,，/]+"))
                .map(String::trim).filter(value -> !value.isBlank()).limit(15).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> source)) return List.of();
        return source.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(String::trim).filter(item -> !item.isBlank()).toList();
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

    private CandidateSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        int matchScore = rs.getInt("match_score");
        Integer matchScoreValue = rs.wasNull() ? null : matchScore;
        return new CandidateSummary(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), pii.decrypt(rs.getString("full_name_ciphertext")),
                pii.decrypt(rs.getString("phone_ciphertext")), pii.decrypt(rs.getString("email_ciphertext")), rs.getString("status"),
                rs.getString("parse_status"), pii.decryptIfEncrypted(rs.getString("original_filename")), rs.getString("headline"),
                rs.getInt("years_experience"), rs.getString("highest_education"), stringsStatic(rs.getString("skills")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                matchScoreValue, rs.getString("matched_job_title"), rs.getString("profile_json"));
    }

    private static List<String> stringsStatic(String json) {
        if (json == null || json.length() < 2) return List.of();
        String body = json.substring(1, json.length() - 1).trim();
        if (body.isBlank()) return List.of();
        return java.util.Arrays.stream(body.split(",")).map(item -> item.trim().replaceAll("^\"|\"$", ""))
                .filter(item -> !item.isBlank()).toList();
    }

    /**
     * Deleted or orphaned file_assets keep their row for audit, but the workspace+sha256 unique index
     * must be released so the same resume can be imported again.
     */
    private void releaseHashSlot(UUID workspaceId, String hash) {
        List<AssetHashRow> lockedAssets = jdbc.query("""
                SELECT f.id, f.sha256 FROM file_assets f
                WHERE f.workspace_id=? AND f.sha256=?
                FOR UPDATE
                """, (rs, rowNum) -> new AssetHashRow(rs.getObject(1, UUID.class), rs.getString(2)),
                workspaceId, hash);
        for (AssetHashRow asset : lockedAssets) {
            if (hasActiveCandidateForAsset(workspaceId, asset.id())) continue;
            jdbc.update("""
                    UPDATE file_assets SET sha256=? WHERE id=? AND workspace_id=?
                    """, archivedSha256(asset.id(), asset.sha256()), asset.id(), workspaceId);
        }
    }

    private boolean hasActiveCandidateForAsset(UUID workspaceId, UUID assetId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM resume_files rf
                JOIN candidates c ON c.id = rf.candidate_id
                WHERE rf.file_asset_id=? AND rf.workspace_id=? AND c.status<>'DELETED'
                """, Integer.class, assetId, workspaceId);
        return count != null && count > 0;
    }

    private static String archivedSha256(UUID assetId, String originalHash) {
        return SecurityHashes.sha256(assetId + ":" + originalHash);
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

    private static Map<String, Object> uploadProfile(ParsedResume parsed) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("source", "简历上传");
        profile.put("skills", parsed.skills());
        profile.put("tags", List.of());
        profile.put("talentStatus", "在库");
        profile.put("activityLevel", "中等活跃");
        profile.put("yearsExperience", String.valueOf(parsed.yearsExperience()));
        profile.put("highestEducation", parsed.education());
        return profile;
    }

    private static boolean looksLikeUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullable(String value) {
        return value == null ? "" : value.trim();
    }

    private static String joinNonBlank(String sep, String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) parts.add(value.trim());
        }
        return String.join(sep, parts);
    }

    private static int parseYears(String value) {
        if (value == null || value.isBlank()) return 0;
        String text = value.trim();
        if (text.contains("应届")) return 0;
        if (text.contains("10")) return 10;
        Matcher matcher = Pattern.compile("(\\d+)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static List<String> mergeSkills(ManualTalentInput input) {
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        for (String part : List.of(
                nullable(input.professionalSkills()),
                nullable(input.softwareSkills()),
                nullable(input.managementSkills()),
                nullable(input.industrySkills()))) {
            for (String skill : part.split("[,，、;\\s]+")) {
                if (!skill.isBlank()) skills.add(skill.trim());
            }
        }
        if (input.tags() != null) {
            for (String tag : input.tags()) if (tag != null && !tag.isBlank()) skills.add(tag.trim());
        }
        return new ArrayList<>(skills);
    }

    private static Map<String, Object> profileMap(ManualTalentInput input, List<String> skills, int years) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("gender", nullable(input.gender()));
        profile.put("province", nullable(input.province()));
        profile.put("city", nullable(input.city()));
        profile.put("district", nullable(input.district()));
        profile.put("currentCompany", nullable(input.currentCompany()));
        profile.put("currentTitle", nullable(input.currentTitle()));
        profile.put("currentLevel", nullable(input.currentLevel()));
        profile.put("yearsExperience", String.valueOf(years));
        profile.put("yearsExperienceLabel", nullable(input.yearsExperience()));
        profile.put("industry", nullable(input.industry()));
        profile.put("highestEducation", nullable(input.highestEducation()));
        profile.put("school", nullable(input.school()));
        profile.put("major", nullable(input.major()));
        profile.put("graduateAt", nullable(input.graduateAt()));
        profile.put("professionalSkills", nullable(input.professionalSkills()));
        profile.put("softwareSkills", nullable(input.softwareSkills()));
        profile.put("managementSkills", nullable(input.managementSkills()));
        profile.put("industrySkills", nullable(input.industrySkills()));
        profile.put("certificates", nullable(input.certificates()));
        profile.put("jobCategory", nullable(input.jobCategory()));
        profile.put("age", nullable(input.age()));
        profile.put("skills", skills);
        profile.put("tags", input.tags() == null ? List.of() : input.tags().stream().filter(t -> t != null && !t.isBlank()).map(String::trim).toList());
        profile.put("source", isBlank(input.source()) ? "手动新增" : input.source().trim());
        profile.put("talentStatus", "在库");
        profile.put("activityLevel", "中等活跃");
        return profile;
    }

    private static String buildSearchText(UUID id, Map<String, Object> profile, List<String> skills, String headline) {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append(' ');
        sb.append(nullable(headline)).append(' ');
        if (skills != null) sb.append(String.join(" ", skills)).append(' ');
        if (profile != null) {
            for (Object value : profile.values()) {
                if (value instanceof List<?> list) sb.append(String.join(" ", list.stream().map(String::valueOf).toList())).append(' ');
                else if (value != null) sb.append(value).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private record ParsedResume(String name, String email, String phone, String headline, int yearsExperience,
                                String education, List<String> skills, List<String> workExperience,
                                List<String> educationExperience, String summary, List<String> warnings,
                                String rawText) { }
    private record AssetReference(UUID id, String objectKey) { }
    private record FileRow(String objectKey, String filename, String mediaType) { }
    private record AssetHashRow(UUID id, String sha256) { }

    public record CandidateSummary(UUID id, UUID companyId, UUID workspaceId, String displayNameMasked, String phone, String email,
                                   String status, String parseStatus, String originalFilename, String headline,
                                   int yearsExperience, String highestEducation, List<String> skills,
                                   Instant createdAt, Instant updatedAt, Integer matchScore, String matchedJobTitle,
                                   String profileJson) { }
    public record CandidateDetail(UUID id, UUID companyId, UUID workspaceId, String displayNameMasked, String phone, String email,
                                  String status, UUID currentParseVersionId, UUID resumeFileId, String parseStatus,
                                  String errorCode, String originalFilename, String mediaType, long sizeBytes,
                                  int parseVersion, String headline, int yearsExperience, String highestEducation,
                                  List<String> skills, List<String> workExperience, List<String> educationExperience,
                                  String summary, List<String> warnings, Instant createdAt, Instant updatedAt,
                                  Integer matchScore, String matchedJobTitle, String profileJson) { }
    public record CandidateListResult(List<CandidateSummary> items, int total, int page, int pageSize) { }
    public record StatPoint(int count, int previousCount, double changePercent) { }
    public record CandidateStats(StatPoint total, StatPoint active, StatPoint highMatch, StatPoint dormant,
                                 StatPoint inPool, int highMatchThreshold) { }
    public record RevealedPii(String fullName, String email, String phone) { }
    public record DownloadedResume(String filename, String mediaType, byte[] content) { }
    public record CandidateListQuery(
            String search, String status, String segment, Integer minMatchScore,
            String industry, String city, String tags, Integer yearsMin, Integer yearsMax,
            String education, String source, String activity, String talentStatus,
            String createdFrom, String createdTo, int page, int pageSize) { }
    public record ManualTalentInput(
            String fullName, String gender, String phone, String email,
            String province, String city, String district,
            String currentCompany, String currentTitle, String currentLevel,
            String yearsExperience, String industry,
            String highestEducation, String school, String major, String graduateAt,
            String professionalSkills, String softwareSkills, String managementSkills, String industrySkills,
            List<String> tags, String source, String certificates, String jobCategory, String age) { }
}
