package com.intelligentrecruitment.candidates.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.aiplatform.application.AiPlatformClient;
import com.intelligentrecruitment.aiplatform.application.StartAiTaskCommand;
import com.intelligentrecruitment.aiplatform.domain.AiCapability;
import com.intelligentrecruitment.aiplatform.domain.AiTask;
import com.intelligentrecruitment.aiplatform.domain.AiTaskStatus;
import com.intelligentrecruitment.agentflow.application.RecruitmentFlowCoordinator;
import com.intelligentrecruitment.agentflow.domain.ExecutionContext;
import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.agentflow.domain.PolicyDecision;
import com.intelligentrecruitment.agentflow.domain.StructuredResult;
import com.intelligentrecruitment.shared.storage.PrivateObjectStorage;
import com.intelligentrecruitment.pools.application.EnterprisePoolService;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import com.intelligentrecruitment.tenancy.application.TenantAccessService;
import com.intelligentrecruitment.tenancy.application.TenantAccessService.TenantScope;
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


    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TenantAccessService tenantAccess;
    private final @org.springframework.beans.factory.annotation.Qualifier("resumeObjectStorage") PrivateObjectStorage storage;
    private final ResumeTextExtractor extractor;
    private final PiiCipher pii;
    private final AiPlatformClient aiPlatform;
    private final EnterprisePoolService enterprisePools;
    private final RecruitmentFlowCoordinator flowCoordinator;
    private final long maxFileSize;
    private org.springframework.transaction.support.TransactionTemplate resultTransaction;

    public CandidateService(JdbcTemplate jdbc, ObjectMapper objectMapper, TenantAccessService tenantAccess,
                            @org.springframework.beans.factory.annotation.Qualifier("resumeObjectStorage") PrivateObjectStorage storage, ResumeTextExtractor extractor, PiiCipher pii, AiPlatformClient aiPlatform,
                            EnterprisePoolService enterprisePools, RecruitmentFlowCoordinator flowCoordinator,
                            @Value("${app.storage.max-file-size-bytes:10485760}") long maxFileSize) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tenantAccess = tenantAccess;
        this.storage = storage;
        this.extractor = extractor;
        this.pii = pii;
        this.aiPlatform = aiPlatform;
        this.enterprisePools = enterprisePools;
        this.flowCoordinator = flowCoordinator;
        this.maxFileSize = maxFileSize;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void configureResultTransaction(org.springframework.transaction.PlatformTransactionManager manager){
        this.resultTransaction=new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    @Transactional
    public CandidateDetail upload(UUID userId, UUID tenantId, MultipartFile file) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        tenantAccess.requirePermission(userId, tenantId, "TALENT_LIBRARY_EDIT");
        byte[] bytes = validateAndRead(file);
        String hash = SecurityHashes.sha256(bytes);
        String duplicateOwner = scope.enterprise() ? " AND c.created_by=?" : "";
        List<Object> duplicateParams = new ArrayList<>(List.of(tenantId, hash));
        if (scope.enterprise()) duplicateParams.add(userId);
        List<UUID> duplicate = jdbc.query("""
                SELECT c.id FROM candidates c JOIN resume_files rf ON rf.candidate_id=c.id
                JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE c.tenant_id=? AND f.sha256=? AND c.status<>'DELETED'""" + duplicateOwner,
                (rs, n) -> rs.getObject(1, UUID.class), duplicateParams.toArray());
        if (!duplicate.isEmpty()) return detailScoped(tenantId, duplicate.getFirst());

        AssetReference existingAsset = activeAsset(tenantId, hash);
        // 活跃资产可能来自简历源文件；优先复用，避免破坏同一文件资产的去重关系。
        // 仅在没有活跃资产时，释放已删除候选人遗留的哈希唯一键。
        if (existingAsset == null) releaseHashSlot(tenantId, hash);
        UUID assetId = existingAsset == null ? UUID.randomUUID() : existingAsset.id();
        UUID candidateId = UUID.randomUUID();
        UUID resumeFileId = UUID.randomUUID();
        String filename = safeFilename(file.getOriginalFilename());
        String objectKey = existingAsset == null ? tenantId + "/" + assetId : existingAsset.objectKey();
        String mediaType = mediaType(filename);
        boolean createdAsset = existingAsset == null;
        if (createdAsset) storage.put(objectKey, bytes, mediaType);
        try {
            return persistUpload(userId, scope, assetId, candidateId, resumeFileId, objectKey, filename,
                    mediaType, bytes, hash, createdAsset);
        } catch (DuplicateKeyException duplicateKey) {
            if (!createdAsset) throw duplicateKey;
            releaseHashSlot(tenantId, hash);
            return persistUpload(userId, scope, assetId, candidateId, resumeFileId, objectKey, filename,
                    mediaType, bytes, hash, true);
        } catch (RuntimeException exception) {
            if (createdAsset) {
                try { storage.remove(objectKey); } catch (RuntimeException ignored) { }
            }
            throw exception;
        }
    }

    protected CandidateDetail persistUpload(UUID userId, TenantScope scope, UUID assetId, UUID candidateId,
                                            UUID resumeFileId, String objectKey, String filename, String mediaType,
                                            byte[] bytes, String hash, boolean createAsset) {
        Instant now = Instant.now();
        String provisionalName = filenameDisplayName(filename);
        if (createAsset) {
            jdbc.update("""
                    INSERT INTO file_assets
                    (id,tenant_id,object_key,original_filename,media_type,size_bytes,sha256,
                     scan_status,lifecycle_status,created_by,created_at)
                    VALUES (?,?,?,?,?,?,?, 'CLEAN','ACTIVE',?,?)
                    """, assetId, scope.tenantId(), objectKey, pii.encrypt(filename), mediaType, bytes.length,
                    hash, userId, timestamp(now));
        }
        jdbc.update("""
                INSERT INTO candidates
                (id,tenant_id,display_name_masked,full_name_ciphertext,email_ciphertext,
                 phone_ciphertext,full_name_search_hash,phone_search_hash,status,created_by,created_at,updated_at,profile,search_text)
                VALUES (?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?::jsonb,?)
                """, candidateId, scope.tenantId(), mask(provisionalName), pii.encrypt(provisionalName),
                pii.encrypt(""), pii.encrypt(""), pii.searchToken(provisionalName), pii.searchToken(""),
                userId, timestamp(now), timestamp(now), json(Map.of("source", "简历上传", "tags", List.of())), provisionalName);
        jdbc.update("""
                INSERT INTO resume_files
                (id,tenant_id,candidate_id,file_asset_id,status,error_code,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, resumeFileId, scope.tenantId(), candidateId, assetId, "QUEUED",
                null, userId, timestamp(now), timestamp(now));
        enqueueResumeParse(scope, userId, candidateId, resumeFileId);
        audit(userId, scope, "RESUME_UPLOADED", candidateId);
        return detailScoped(scope.tenantId(), candidateId);
    }

    /**
     * file_assets deduplicates binary files within a tenant. A resume may first
     * have been uploaded as a task source file, so it may not yet have a candidate
     * record. Reuse that active asset instead of attempting a second insert.
     */
    private AssetReference activeAsset(UUID tenantId, String sha256) {
        List<AssetReference> rows = jdbc.query("""
                SELECT id,object_key FROM file_assets
                WHERE tenant_id=? AND sha256=? AND lifecycle_status='ACTIVE'
                """, (rs, n) -> new AssetReference(rs.getObject(1, UUID.class), rs.getString(2)), tenantId, sha256);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public CandidateStats stats(UUID userId, UUID tenantId) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        StatPoint total = metric(
                countTotal(scope, userId, null),
                countTotal(scope, userId, "PREV_MONTH_END"));
        StatPoint active = metric(
                countActive(scope, userId, false),
                countActive(scope, userId, true));
        StatPoint highMatch = metric(
                countHighMatch(scope, userId, false),
                countHighMatch(scope, userId, true));
        StatPoint dormant = metric(
                countDormant(scope, userId, false),
                countDormant(scope, userId, true));
        StatPoint inPool = metric(
                countInPool(scope, userId, null),
                countInPool(scope, userId, "PREV_MONTH_END"));
        return new CandidateStats(total, active, highMatch, dormant, inPool, 80);
    }

    public CandidateListResult list(UUID userId, UUID tenantId, CandidateListQuery query) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        int safePage = Math.max(1, query.page());
        // 筛选工作台需要一次加载最多 200 位已解析候选人；保持旧接口容量，
        // 同时使用远端新增的统一查询条件。
        int safeSize = Math.min(200, Math.max(1, query.pageSize()));
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        StringBuilder where = new StringBuilder(" WHERE c.tenant_id=? AND c.status<>'DELETED'");
        if (scope.enterprise()) { where.append(" AND c.created_by=?"); params.add(userId); }
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
                       WHERE sri.candidate_id = c.id AND sri.tenant_id = c.tenant_id
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
                       WHERE sri.candidate_id = c.id AND sri.tenant_id = c.tenant_id
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
            where.append(" AND COALESCE(NULLIF(c.profile->>'yearsExperience','')::numeric, pv.years_experience) >= ?");
            params.add(query.yearsMin());
        }
        if (query.yearsMax() != null) {
            where.append(" AND COALESCE(NULLIF(c.profile->>'yearsExperience','')::numeric, pv.years_experience) <= ?");
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
                    WHERE sri.candidate_id = c.id AND sri.tenant_id = c.tenant_id
                    ORDER BY sr.score DESC, sr.created_at DESC
                    LIMIT 1
                ) ms ON TRUE
                """;
        Integer total = jdbc.queryForObject("SELECT count(DISTINCT c.id) " + joins + where, Integer.class, params.toArray());
        List<CandidateSummary> items = jdbc.query("""
                SELECT c.id,c.tenant_id,c.tenant_id,c.full_name_ciphertext,c.phone_ciphertext,c.email_ciphertext,c.status,
                       COALESCE(rf.status, 'PARSED') AS parse_status,COALESCE(f.original_filename, '手动录入') AS original_filename,
                       COALESCE(pv.headline, CONCAT_WS(' | ', c.profile->>'currentTitle', c.profile->>'currentCompany')) AS headline,
                       COALESCE(NULLIF(c.profile->>'yearsExperience','')::numeric, pv.years_experience) AS years_experience,
                       COALESCE(c.profile->>'highestEducation', pv.highest_education, '') AS highest_education,
                       COALESCE(c.profile->'skills', pv.skills, '[]'::jsonb)::text AS skills,
                       c.created_at,c.updated_at,ms.match_score,ms.matched_job_title,c.profile::text AS profile_json
                """ + joins + where + " ORDER BY c.updated_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> summary(rs), concat(params, safeSize, (safePage - 1) * safeSize));
        return new CandidateListResult(items, total == null ? 0 : total, safePage, safeSize);
    }

    public CandidateListResult list(UUID userId, UUID tenantId, String search, String status, String segment,
                                    Integer minMatchScore, int page, int pageSize) {
        return list(userId, tenantId, new CandidateListQuery(search, status, segment, minMatchScore,
                null, null, null, null, null, null, null, null, null, null, null, page, pageSize));
    }

    public CandidateListResult list(UUID userId, UUID tenantId, String search, String status, int page, int pageSize) {
        return list(userId, tenantId, search, status, null, null, page, pageSize);
    }

    @Transactional
    public CandidateDetail createManual(UUID userId, UUID tenantId, ManualTalentInput input) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        tenantAccess.requirePermission(userId, tenantId, "TALENT_LIBRARY_EDIT");
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
        java.math.BigDecimal years = parseYears(input.yearsExperience());
        Map<String, Object> profile = profileMap(input, skills, years);
        profile.put("manualConfirmed",true);
        String searchText = buildSearchText(candidateId, profile, skills, headline);
        String filename = "manual-" + candidateId + ".txt";
        String objectKey = tenantId + "/" + assetId;
        byte[] bytes = ("手动录入人才档案\n" + searchText).getBytes(StandardCharsets.UTF_8);
        storage.put(objectKey, bytes, "text/plain");
        try {
            jdbc.update("""
                    INSERT INTO file_assets
                    (id,tenant_id,object_key,original_filename,media_type,size_bytes,sha256,
                     scan_status,lifecycle_status,created_by,created_at)
                    VALUES (?,?,?,?,?,?,?,'CLEAN','ACTIVE',?,?)
                    """, assetId, scope.tenantId(), objectKey, pii.encrypt(filename), "text/plain",
                    bytes.length, SecurityHashes.sha256(bytes), userId, timestamp(now));
            jdbc.update("""
                    INSERT INTO candidates
                    (id,tenant_id,display_name_masked,full_name_ciphertext,email_ciphertext,
                     phone_ciphertext,full_name_search_hash,phone_search_hash,status,created_by,created_at,updated_at,profile,search_text)
                    VALUES (?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?::jsonb,?)
                    """, candidateId, scope.tenantId(), mask(name), pii.encrypt(name),
                    pii.encrypt(email), pii.encrypt(phone), pii.searchToken(name), pii.searchToken(phone), userId, timestamp(now), timestamp(now),
                    json(profile), searchText);
            jdbc.update("""
                    INSERT INTO resume_files
                    (id,tenant_id,candidate_id,file_asset_id,status,error_code,created_by,created_at,updated_at)
                    VALUES (?,?,?,?,'PARSED',NULL,?,?,?)
                    """, resumeFileId, scope.tenantId(), candidateId, assetId, userId,
                    timestamp(now), timestamp(now));
            ParsedResume parsed = new ParsedResume(name, email == null ? "" : email, phone == null ? "" : phone,
                    headline, years, nullable(input.highestEducation()).isBlank() ? "待确认" : input.highestEducation().trim(),
                    skills,
                    List.of(joinNonBlank(" / ", nullable(input.currentCompany()), nullable(input.currentTitle()))),
                    List.of(joinNonBlank(" · ", nullable(input.school()), nullable(input.major()), nullable(input.highestEducation()))),
                    "手动录入人才档案", List.of(), "手动录入人才档案", Map.of());
            saveParseVersion(scope, candidateId, resumeFileId, 1, parsed, now);
            audit(userId, scope, "CANDIDATE_CREATED_MANUAL", candidateId);
            CandidateDetail detail=detailScoped(scope.tenantId(), candidateId); enterprisePools.syncCandidate(scope,userId,candidateId); return detail;
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
    public CandidateDetail createFromResumeSource(UUID userId, UUID tenantId, UUID assetId,
                                                  String filename, String extractedText) {
        return createFromResumeSource(userId,tenantId,assetId,filename,extractedText,null);
    }

    @Transactional
    public CandidateDetail createFromResumeSource(UUID userId,UUID tenantId,UUID assetId,String filename,String extractedText,StructuredResult savedResult){
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        tenantAccess.requirePermission(userId, tenantId, "TALENT_LIBRARY_EDIT");
        // 幂等：该简历资产已入库为候选人则直接返回已有候选人
        String linkedOwner = scope.enterprise() ? " AND c.created_by=?" : "";
        List<Object> linkedParams = new ArrayList<>(List.of(assetId, tenantId));
        if (scope.enterprise()) linkedParams.add(userId);
        List<UUID> linked = jdbc.query("""
                SELECT c.id FROM candidates c
                JOIN resume_files rf ON rf.candidate_id=c.id
                WHERE rf.file_asset_id=? AND c.tenant_id=? AND c.status<>'DELETED'""" + linkedOwner,
                (rs, n) -> rs.getObject(1, UUID.class), linkedParams.toArray());
        if (!linked.isEmpty()) return detailScoped(tenantId, linked.getFirst());
        // 资产必须存在且属于当前租户
        Integer assetCount = jdbc.queryForObject(
                "SELECT count(*) FROM file_assets WHERE id=? AND tenant_id=? AND lifecycle_status='ACTIVE'",
                Integer.class, assetId, tenantId);
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
                (id,tenant_id,display_name_masked,full_name_ciphertext,email_ciphertext,
                 phone_ciphertext,full_name_search_hash,phone_search_hash,status,created_by,created_at,updated_at,profile,search_text)
                VALUES (?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,?::jsonb,?)
                """, candidateId, scope.tenantId(), mask(safeName), pii.encrypt(safeName),
                pii.encrypt(""), pii.encrypt(""), pii.searchToken(safeName), pii.searchToken(""),
                userId, timestamp(now), timestamp(now),
                json(Map.of("source", "AI简历解析", "tags", List.of())), safeName);
        jdbc.update("""
                INSERT INTO resume_files
                (id,tenant_id,candidate_id,file_asset_id,status,error_code,created_by,created_at,updated_at)
                VALUES (?,?,?,?,'QUEUED',NULL,?,?,?)
                """, resumeFileId, scope.tenantId(), candidateId, assetId, userId,
                timestamp(now), timestamp(now));
        // 复用源附件后仍由统一的 BOSS 授权 AI 异步任务解析；不得在招聘服务内直接解析。
        if(savedResult!=null){
            Map<String,Object> data=savedResult.data();
            if(!(data.get("parsed") instanceof Map<?,?> direct))throw new ApiException("AI_CONTRACT_INVALID","结构化简历结果缺失，不能从分析正文猜测候选人字段",HttpStatus.BAD_GATEWAY);
            ParsedResume parsed=parsedFromDirect(filename,extractedText,direct,data.getOrDefault("overall_assessment",data.get("analysis_text")),data.get("warnings"));
            saveParsedResume(scope,candidateId,resumeFileId,parsed,1);
        }else enqueueResumeParse(scope, userId, candidateId, resumeFileId);
        audit(userId, scope, "CANDIDATE_CREATED_FROM_RESUME_PARSE", candidateId);
        CandidateDetail detail=detailScoped(tenantId, candidateId); enterprisePools.syncCandidate(scope,userId,candidateId); return detail;
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


    private int countTotal(TenantScope scope, UUID userId, String mode) {
        UUID tenantId = scope.tenantId();
        String owner = scope.enterprise() ? " AND created_by=?" : "";
        Object[] params = scope.enterprise() ? new Object[]{tenantId, userId} : new Object[]{tenantId};
        if ("PREV_MONTH_END".equals(mode)) {
            return value(jdbc.queryForObject("""
                    SELECT count(*) FROM candidates
                    WHERE tenant_id=? AND created_at < date_trunc('month', CURRENT_TIMESTAMP)
                      AND (status<>'DELETED' OR updated_at >= date_trunc('month', CURRENT_TIMESTAMP))
                    """ + owner,
                    Integer.class, params));
        }
        return value(jdbc.queryForObject(
                "SELECT count(*) FROM candidates WHERE tenant_id=? AND status<>'DELETED'" + owner,
                Integer.class, params));
    }

    private int countActive(TenantScope scope, UUID userId, boolean previousWindow) {
        UUID tenantId = scope.tenantId(); String owner = scope.enterprise() ? " AND created_by=?" : "";
        Object[] params = scope.enterprise() ? new Object[]{tenantId, userId} : new Object[]{tenantId};
        if (previousWindow) {
            return value(jdbc.queryForObject("""
                    SELECT count(*) FROM candidates
                    WHERE tenant_id=? AND status<>'DELETED'
                      AND updated_at >= (date_trunc('month', CURRENT_TIMESTAMP) - INTERVAL '30 days')
                      AND updated_at < date_trunc('month', CURRENT_TIMESTAMP)
                    """ + owner, Integer.class, params));
        }
        return value(jdbc.queryForObject("""
                SELECT count(*) FROM candidates
                WHERE tenant_id=? AND status<>'DELETED'
                  AND updated_at >= (CURRENT_TIMESTAMP - INTERVAL '30 days')
                """ + owner, Integer.class, params));
    }

    private int countHighMatch(TenantScope scope, UUID userId, boolean previousMonth) {
        UUID tenantId = scope.tenantId(); String owner = scope.enterprise() ? " AND c.created_by=?" : "";
        Object[] params = scope.enterprise() ? new Object[]{tenantId, userId} : new Object[]{tenantId};
        if (previousMonth) {
            return value(jdbc.queryForObject("""
                    SELECT count(DISTINCT sri.candidate_id)
                    FROM screening_run_items sri
                    JOIN screening_results sr ON sr.run_item_id = sri.id
                    JOIN candidates c ON c.id = sri.candidate_id
                    WHERE sri.tenant_id=? AND c.status<>'DELETED'
                      AND sr.score >= 80
                      AND sr.created_at < date_trunc('month', CURRENT_TIMESTAMP)
                    """ + owner,
                    Integer.class, params));
        }
        return value(jdbc.queryForObject("""
                SELECT count(DISTINCT sri.candidate_id)
                FROM screening_run_items sri
                JOIN screening_results sr ON sr.run_item_id = sri.id
                JOIN candidates c ON c.id = sri.candidate_id
                WHERE sri.tenant_id=? AND c.status<>'DELETED' AND sr.score >= 80
                """ + owner, Integer.class, params));
    }

    private int countDormant(TenantScope scope, UUID userId, boolean previousMonth) {
        UUID tenantId = scope.tenantId(); String owner = scope.enterprise() ? " AND created_by=?" : "";
        Object[] params = scope.enterprise() ? new Object[]{tenantId, userId} : new Object[]{tenantId};
        if (previousMonth) {
            return value(jdbc.queryForObject("""
                    SELECT count(*) FROM candidates
                    WHERE tenant_id=? AND status<>'DELETED'
                      AND created_at < date_trunc('month', CURRENT_TIMESTAMP)
                      AND updated_at < (date_trunc('month', CURRENT_TIMESTAMP) - INTERVAL '90 days')
                    """ + owner,
                    Integer.class, params));
        }
        return value(jdbc.queryForObject("""
                SELECT count(*) FROM candidates
                WHERE tenant_id=? AND status<>'DELETED'
                  AND updated_at < (CURRENT_TIMESTAMP - INTERVAL '90 days')
                """ + owner, Integer.class, params));
    }

    private int countInPool(TenantScope scope, UUID userId, String mode) {
        UUID tenantId = scope.tenantId(); String owner = scope.enterprise() ? " AND c.created_by=?" : "";
        Object[] params = scope.enterprise() ? new Object[]{tenantId, userId} : new Object[]{tenantId};
        if ("PREV_MONTH_END".equals(mode)) {
            return value(jdbc.queryForObject("""
                    SELECT count(DISTINCT c.id)
                    FROM candidates c
                    JOIN resume_files rf ON rf.candidate_id = c.id
                    WHERE c.tenant_id=? AND c.status<>'DELETED' AND rf.status='PARSED'
                      AND c.created_at < date_trunc('month', CURRENT_TIMESTAMP)
                    """ + owner,
                    Integer.class, params));
        }
        return value(jdbc.queryForObject("""
                SELECT count(DISTINCT c.id)
                FROM candidates c
                JOIN resume_files rf ON rf.candidate_id = c.id
                WHERE c.tenant_id=? AND c.status<>'DELETED' AND rf.status='PARSED'
                """ + owner, Integer.class, params));
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

    public CandidateDetail get(UUID userId, UUID tenantId, UUID candidateId) {
        TenantScope scope=tenantAccess.requireBusinessAccess(userId, tenantId); requireSourceOwner(scope,userId,candidateId);
        return detailScoped(tenantId, candidateId);
    }

    public RevealedPii reveal(UUID userId, UUID tenantId, UUID candidateId) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        requireSourceOwner(scope,userId,candidateId);
        List<RevealedPii> rows = jdbc.query("""
                SELECT full_name_ciphertext,email_ciphertext,phone_ciphertext FROM candidates
                WHERE id=? AND tenant_id=? AND status<>'DELETED'
                """, (rs, n) -> new RevealedPii(pii.decrypt(rs.getString(1)), pii.decrypt(rs.getString(2)),
                pii.decrypt(rs.getString(3))), candidateId, tenantId);
        if (rows.isEmpty()) throw notFound();
        audit(userId, scope, "CANDIDATE_PII_REVEALED", candidateId);
        return rows.getFirst();
    }

    @Transactional
    public DownloadedResume download(UUID userId, UUID tenantId, UUID candidateId) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        requireSourceOwner(scope,userId,candidateId);
        List<FileRow> rows = jdbc.query("""
                SELECT f.object_key,f.original_filename,f.media_type FROM candidates c
                JOIN resume_files rf ON rf.candidate_id=c.id JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE c.id=? AND c.tenant_id=? AND c.status<>'DELETED' AND f.lifecycle_status='ACTIVE'
                """, (rs, n) -> new FileRow(rs.getString(1), rs.getString(2), rs.getString(3)), candidateId, tenantId);
        if (rows.isEmpty()) throw notFound();
        FileRow file = rows.getFirst();
        byte[] content = storage.get(file.objectKey());
        audit(userId, scope, "RESUME_DOWNLOADED", candidateId);
        return new DownloadedResume(pii.decryptIfEncrypted(file.filename()), file.mediaType(), content);
    }

    @Transactional
    public CandidateDetail retryParse(UUID userId, UUID tenantId, UUID candidateId) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        tenantAccess.requirePermission(userId, tenantId, "TALENT_LIBRARY_EDIT");
        requireSourceOwner(scope,userId,candidateId);
        jdbc.queryForList("SELECT id FROM candidates WHERE id=? AND tenant_id=? FOR UPDATE",candidateId,tenantId);
        CandidateDetail existing = detailScoped(tenantId, candidateId);
        if(!java.util.Set.of("PARSED","FAILED").contains(existing.parseStatus()))throw new ApiException("EXECUTION_NOT_TERMINAL","前次解析尚未结束",HttpStatus.CONFLICT);
        Integer pending=jdbc.queryForObject("SELECT count(*) FROM ai_execution_records WHERE tenant_id=? AND business_task_id=? AND capability='RESUME_PARSING' AND (final_decision IS NULL OR status NOT IN ('SETTLED','RELEASED'))",Integer.class,tenantId,candidateId.toString());
        if(pending!=null&&pending>0)throw new ApiException("SETTLEMENT_PENDING","前次解析尚未完成结算，暂不能重跑",HttpStatus.CONFLICT);
        jdbc.update("UPDATE resume_files SET status='QUEUED',error_code=NULL,provider_task_id=NULL,updated_at=? WHERE id=? AND tenant_id=?",
                timestamp(Instant.now()), existing.resumeFileId(), tenantId);
        enqueueResumeParse(scope, userId, candidateId, existing.resumeFileId());
        audit(userId, scope, "RESUME_PARSE_RETRIED", candidateId);
        return detailScoped(tenantId, candidateId);
    }

    @Transactional
    public void delete(UUID userId, UUID tenantId, UUID candidateId) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        tenantAccess.requirePermission(userId, tenantId, "TALENT_LIBRARY_EDIT");
        requireSourceOwner(scope,userId,candidateId);
        List<FileRow> files = jdbc.query("""
                SELECT f.object_key,f.original_filename,f.media_type FROM candidates c
                JOIN resume_files rf ON rf.candidate_id=c.id JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE c.id=? AND c.tenant_id=? AND c.status<>'DELETED'
                """, (rs, n) -> new FileRow(rs.getString(1), rs.getString(2), rs.getString(3)), candidateId, tenantId);
        jdbc.update("UPDATE candidates SET status='DELETED',updated_at=? WHERE id=? AND tenant_id=?",
                timestamp(Instant.now()), candidateId, tenantId);
        for (FileRow file : files) {
            jdbc.update("UPDATE file_assets SET lifecycle_status='DELETING' WHERE object_key=? AND tenant_id=?",
                    file.objectKey(), tenantId);
            try { storage.remove(file.objectKey()); } catch (RuntimeException ignored) { }
            List<AssetHashRow> assets = jdbc.query("""
                    SELECT id, sha256 FROM file_assets WHERE object_key=? AND tenant_id=?
                    """, (rs, rowNum) -> new AssetHashRow(rs.getObject(1, UUID.class), rs.getString(2)),
                    file.objectKey(), tenantId);
            for (AssetHashRow asset : assets) {
                jdbc.update("""
                        UPDATE file_assets
                        SET lifecycle_status='DELETED', sha256=?
                        WHERE id=? AND tenant_id=?
                        """, archivedSha256(asset.id(), asset.sha256()), asset.id(), tenantId);
            }
        }
        audit(userId, scope, "CANDIDATE_DELETED", candidateId);
    }

    private void saveParseVersion(TenantScope scope, UUID candidateId, UUID resumeFileId, int version,
                                  ParsedResume parsed, Instant now) {
        UUID parseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO resume_parse_versions
                (id,tenant_id,candidate_id,resume_file_id,version_number,schema_version,status,
                headline,years_experience,highest_education,skills,work_experience,education_experience,structured_data,
                 summary,warnings,raw_text,created_at)
                VALUES (?,?,?,?,?,'RESUME_V1','CONFIRMED',?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?::jsonb,?,?)
                """, parseId, scope.tenantId(), candidateId, resumeFileId, version,
                parsed.headline(), parsed.yearsExperience(), parsed.education(), json(parsed.skills()),
                json(parsed.workExperience()), json(parsed.educationExperience()), json(Map.of("ciphertext",pii.encrypt(json(parsed.structuredData())))), parsed.summary(),
                json(parsed.warnings()), pii.encrypt(parsed.rawText()), timestamp(now));
        jdbc.update("UPDATE candidates SET current_parse_version_id=?,updated_at=? WHERE id=? AND tenant_id=? AND (current_parse_version_id IS NULL OR COALESCE(profile->>'manualConfirmed','false')<>'true')",
                parseId, timestamp(now), candidateId, scope.tenantId());
    }

    private CandidateDetail detailScoped(UUID tenantId, UUID candidateId) {
        List<CandidateDetail> rows = jdbc.query("""
                SELECT c.id,c.tenant_id,c.full_name_ciphertext,c.phone_ciphertext,c.email_ciphertext,c.status,c.current_parse_version_id,
                       rf.id AS resume_file_id,rf.status AS parse_status,rf.error_code,
                       f.original_filename,f.media_type,f.size_bytes,
                       COALESCE(NULLIF(c.profile->>'yearsExperience','')::numeric, pv.years_experience) AS years_experience,
                       COALESCE(c.profile->>'highestEducation', pv.highest_education, '') AS highest_education,
                       COALESCE(c.profile->'skills', pv.skills, '[]'::jsonb)::text AS skills,
                       COALESCE(pv.headline, CONCAT_WS(' | ', c.profile->>'currentTitle', c.profile->>'currentCompany')) AS headline,
                       pv.version_number,pv.work_experience::text,pv.education_experience::text,pv.summary,pv.warnings::text,pv.structured_data::text AS structured_data_json,
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
                    WHERE sri.candidate_id = c.id AND sri.tenant_id = c.tenant_id
                    ORDER BY sr.score DESC NULLS LAST, sri.created_at DESC
                    LIMIT 1
                ) ms ON TRUE
                WHERE c.id=? AND c.tenant_id=? AND c.status<>'DELETED'
                """, (rs, n) -> {
            java.math.BigDecimal score = rs.getBigDecimal("match_score");
            return new CandidateDetail(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                pii.decrypt(rs.getString("full_name_ciphertext")), pii.decrypt(rs.getString("phone_ciphertext")), pii.decrypt(rs.getString("email_ciphertext")), rs.getString("status"),
                rs.getObject("current_parse_version_id", UUID.class), rs.getObject("resume_file_id", UUID.class),
                rs.getString("parse_status"), rs.getString("error_code"), pii.decryptIfEncrypted(rs.getString("original_filename")),
                rs.getString("media_type"), rs.getLong("size_bytes"), rs.getInt("version_number"),
                rs.getString("headline"), rs.getBigDecimal("years_experience"), rs.getString("highest_education"),
                strings(rs.getString("skills")), experienceDescriptions(rs.getString("work_experience")),
                experienceDescriptions(rs.getString("education_experience")), rs.getString("summary"),
                strings(rs.getString("warnings")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), score, rs.getString("matched_job_title"),
                rs.getString("profile_json"),sanitizedStructuredResume(rs.getString("structured_data_json")));
        }, candidateId, tenantId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    @Transactional
    public CandidateDetail confirmProfile(UUID userId,UUID tenantId,UUID candidateId,ProfileConfirmation input){
        TenantScope scope=tenantAccess.requireBusinessAccess(userId,tenantId);
        tenantAccess.requirePermission(userId,tenantId,"TALENT_LIBRARY_EDIT");requireSourceOwner(scope,userId,candidateId);
        jdbc.queryForList("SELECT id FROM candidates WHERE id=? AND tenant_id=? FOR UPDATE",candidateId,tenantId);
        CandidateDetail existing=detailScoped(tenantId,candidateId);
        Map<String,Object> profile=parseProfileMap(existing.profileJson());
        if(input.profile()!=null)for(var entry:input.profile().entrySet()){
            if(!java.util.Set.of("yearsExperience","highestEducation","skills","currentCompany","currentTitle","school","major","tags").contains(entry.getKey()))throw validation("不支持确认该资料字段");
            if("yearsExperience".equals(entry.getKey())&&entry.getValue()!=null){var years=decimalValue(entry.getValue());if(years==null||years.signum()<0||years.compareTo(java.math.BigDecimal.valueOf(100))>0)throw validation("工作年限无效");profile.put(entry.getKey(),years);}
            else profile.put(entry.getKey(),entry.getValue());
        }
        profile.put("manualConfirmed",true);profile.put("manualConfirmedAt",Instant.now().toString());profile.put("manualConfirmedBy",userId.toString());
        String name=input.fullName()==null?existing.displayNameMasked():input.fullName();String phone=input.phone()==null?existing.phone():input.phone();String email=input.email()==null?existing.email():input.email();
        jdbc.update("UPDATE candidates SET profile=?::jsonb,display_name_masked=?,full_name_ciphertext=?,phone_ciphertext=?,email_ciphertext=?,full_name_search_hash=?,phone_search_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND tenant_id=?",json(profile),mask(name),pii.encrypt(name),pii.encrypt(phone),pii.encrypt(email),pii.searchToken(name),pii.searchToken(phone),candidateId,tenantId);
        audit(userId,scope,"CANDIDATE_PROFILE_CONFIRMED",candidateId);return detailScoped(tenantId,candidateId);
    }
    public record ProfileConfirmation(String fullName,String phone,String email,Map<String,Object> profile){}

    @Transactional
    public CandidateDetail updateTags(UUID userId, UUID tenantId, UUID candidateId, List<String> tags) {
        TenantScope scope=tenantAccess.requireBusinessAccess(userId, tenantId);
        tenantAccess.requirePermission(userId, tenantId, "TALENT_LIBRARY_EDIT");
        requireSourceOwner(scope,userId,candidateId);
        CandidateDetail existing = detailScoped(tenantId, candidateId);
        Map<String, Object> profile = parseProfileMap(existing.profileJson());
        List<String> cleaned = tags == null ? List.of() : tags.stream()
                .filter(t -> t != null && !t.isBlank()).map(String::trim).distinct().toList();
        profile.put("tags", cleaned);
        String searchText = buildSearchText(existing.id(),
                profile, existing.skills(), existing.headline());
        jdbc.update("UPDATE candidates SET profile=?::jsonb, search_text=?, updated_at=? WHERE id=? AND tenant_id=?",
                json(profile), searchText, timestamp(Instant.now()), candidateId, tenantId);
        CandidateDetail detail=detailScoped(tenantId, candidateId); enterprisePools.syncCandidate(scope,userId,candidateId); return detail;
    }

    private void requireSourceOwner(TenantScope scope, UUID userId, UUID candidateId) {
        if (!scope.enterprise()) return;
        Integer n=jdbc.queryForObject("SELECT count(*) FROM candidates WHERE id=? AND tenant_id=? AND created_by=? AND status<>'DELETED'",Integer.class,candidateId,scope.tenantId(),userId);
        if(n==null||n==0)throw new ApiException("CANDIDATE_SOURCE_FORBIDDEN","只能访问自己的个人人才数据",HttpStatus.FORBIDDEN);
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

    private FileRow fileRow(UUID tenantId, UUID candidateId) {
        List<FileRow> rows = jdbc.query("""
                SELECT f.object_key,f.original_filename,f.media_type FROM candidates c
                JOIN resume_files rf ON rf.candidate_id=c.id JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE c.id=? AND c.tenant_id=? AND c.status<>'DELETED'
                """, (rs, n) -> new FileRow(rs.getString(1), rs.getString(2), rs.getString(3)), candidateId, tenantId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private void saveParsedResume(TenantScope scope, UUID candidateId, UUID resumeFileId,
                                  ParsedResume parsed, int version) {
        Instant now = Instant.now();
        saveParseVersion(scope, candidateId, resumeFileId, version, parsed, now);
        Map<String,Object> existing=jdbc.query("SELECT profile::text FROM candidates WHERE id=? AND tenant_id=? FOR UPDATE",(rs,n)->parseProfileMap(rs.getString(1)),candidateId,scope.tenantId()).getFirst();
        Map<String, Object> profile = mergeParsedProfile(uploadProfile(parsed),existing);
        if(Boolean.TRUE.equals(existing.get("manualConfirmed"))) {
            jdbc.update("UPDATE candidates SET profile=?::jsonb,updated_at=? WHERE id=? AND tenant_id=?",json(profile),timestamp(now),candidateId,scope.tenantId());
        } else jdbc.update("""
                UPDATE candidates SET display_name_masked=?,full_name_ciphertext=?,email_ciphertext=?,phone_ciphertext=?,
                full_name_search_hash=?,phone_search_hash=?,profile=?::jsonb,search_text=?,updated_at=?
                WHERE id=? AND tenant_id=?
                """, mask(parsed.name()), pii.encrypt(parsed.name()), pii.encrypt(parsed.email()), pii.encrypt(parsed.phone()),
                pii.searchToken(parsed.name()), pii.searchToken(parsed.phone()), json(profile),
                buildSearchText(candidateId, profile, parsed.skills(), parsed.headline()), timestamp(now),
                candidateId, scope.tenantId());
        // 兜底与成功都视为已解析（有可用简历原文）；失败码清空，结构化完善程度通过解析版本 warnings 体现
        jdbc.update("UPDATE resume_files SET status='PARSED',error_code=NULL,updated_at=? WHERE id=? AND tenant_id=?",
                timestamp(now), resumeFileId, scope.tenantId());
    }

    private void enqueueResumeParse(TenantScope scope, UUID userId, UUID candidateId, UUID resumeFileId) {
        String idempotencyKey = "candidate-resume:" + resumeFileId + ":" + UUID.randomUUID();
        Instant now = Instant.now();
        String hash=jdbc.queryForObject("SELECT f.sha256 FROM resume_files rf JOIN file_assets f ON f.id=rf.file_asset_id WHERE rf.id=? AND rf.tenant_id=?",String.class,resumeFileId,scope.tenantId());
        ExecutionContext frozen=flowCoordinator.createExecutionContext(flowCoordinator.evaluateAuthoritative(FlowCapability.RESUME_PARSING,scope,userId),candidateId,idempotencyKey,"candidate-resume-parse:"+resumeFileId,
                List.of(new ExecutionContext.InputVersion("resume_file",resumeFileId.toString(),"uploaded",hash)),true);
        jdbc.update("UPDATE resume_files SET execution_context=?::jsonb WHERE id=? AND tenant_id=?",json(frozen),resumeFileId,scope.tenantId());
        jdbc.update("UPDATE resume_files SET parse_idempotency_key=?,parse_attempts=0,enterprise_pool_sync_enabled=? WHERE id=? AND tenant_id=?",
                idempotencyKey, scope.enterprise() && scope.talentPoolSharingEnabled(), resumeFileId, scope.tenantId());
        jdbc.update("""
                INSERT INTO outbox_events
                (id,aggregate_type,aggregate_id,event_type,payload,status,attempts,next_attempt_at,created_at)
                VALUES (?,'RESUME_FILE',?,'CANDIDATE_RESUME_PARSE_REQUESTED',?::jsonb,'PENDING',0,?,?)
                """, UUID.randomUUID(), resumeFileId.toString(), json(Map.of(
                        "candidate_id", candidateId.toString(), "actor_user_id", userId.toString())),
                timestamp(now), timestamp(now));
    }

    @Transactional
    public ResumeParseOutboxClaim claimNextResumeParse() {
        Instant now = Instant.now();
        List<ResumeParseOutboxClaim> rows = jdbc.query("""
                UPDATE outbox_events SET status='PROCESSING',attempts=attempts+1,next_attempt_at=?
                WHERE id=(SELECT id FROM outbox_events
                    WHERE event_type='CANDIDATE_RESUME_PARSE_REQUESTED'
                      AND ((status='PENDING' AND (next_attempt_at IS NULL OR next_attempt_at<=?))
                        OR (status='PROCESSING' AND next_attempt_at<=?))
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id,aggregate_id,attempts
                """, (rs, n) -> new ResumeParseOutboxClaim(rs.getObject("id", UUID.class),
                UUID.fromString(rs.getString("aggregate_id")), rs.getInt("attempts")),
                timestamp(now.plusSeconds(300)), timestamp(now), timestamp(now));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void startResumeParse(UUID resumeFileId) {
        CandidateParseJob job = candidateParseJob(resumeFileId, false);
        if (!"QUEUED".equals(job.status())) return;
        byte[] resumeBytes = storage.get(job.objectKey());
        String rawText = extractor.extract(resumeBytes, job.filename());
        TenantScope scope = new TenantScope(job.tenantId(), null, null, null);
        ExecutionContext context;
        try { context=objectMapper.readValue(job.executionContext(),ExecutionContext.class); }
        catch(Exception ex){throw new ApiException("FROZEN_ATTEMPT_REQUIRED","简历执行缺少创建时冻结的快照",HttpStatus.CONFLICT);}
        Map<String, Object> resumeInput = new LinkedHashMap<>();
        resumeInput.put("filename", job.filename()); resumeInput.put("text", rawText); resumeInput.put("source", "upload");
        if ("complex_recruitment_agent".equals(context.agentRoute().agentId())) {
            resumeInput.put("file_asset_id",job.fileAssetId().toString());resumeInput.put("sha256",job.sha256());
            resumeInput.put("mime_type",job.mediaType());resumeInput.put("byte_count",job.sizeBytes());
        }
        AiTask task = aiPlatform.startTask(new StartAiTaskCommand(job.tenantId().toString(), job.actorUserId().toString(),
                job.candidateId().toString(), job.idempotencyKey(), AiCapability.RESUME_PARSING,
                Map.of("resumes", List.of(resumeInput),
                        "job", Map.of()), context));
        jdbc.update("UPDATE resume_files SET status='PROCESSING',provider_task_id=?,parse_attempts=parse_attempts+1,updated_at=? WHERE id=? AND tenant_id=?",
                UUID.fromString(task.aiTaskId()), timestamp(Instant.now()), resumeFileId, job.tenantId());
    }

    public List<UUID> runningResumeParseIds() {
        return jdbc.query("SELECT id FROM resume_files WHERE status='PROCESSING' AND provider_task_id IS NOT NULL ORDER BY updated_at LIMIT 50",
                (rs, n) -> rs.getObject(1, UUID.class));
    }

    public void finalizeResumeParseIfReady(UUID resumeFileId) {
        CandidateParseJob job = candidateParseJob(resumeFileId, false);
        if (!"PROCESSING".equals(job.status()) || job.providerTaskId() == null) return;
        AiTask task = aiPlatform.getTask(job.providerTaskId().toString(), job.actorUserId().toString());
        if (task.status() == AiTaskStatus.FAILED || task.status() == AiTaskStatus.CANCELLED) {
            resultTransaction.executeWithoutResult(tx->{
                CandidateParseJob current=candidateParseJob(resumeFileId,true);
                if("PROCESSING".equals(current.status())&&java.util.Objects.equals(current.providerTaskId(),job.providerTaskId()))
                    failResumeParse(current,task.errorCode()==null?"AI_PROVIDER_UNAVAILABLE":task.errorCode(),
                            task.errorMessage()==null?"AI 简历解析失败，请重试":task.errorMessage());
            });
            return;
        }
        if (task.status() != AiTaskStatus.COMPLETED) return;
        StructuredResult result = aiPlatform.getStructuredResult(job.providerTaskId().toString(), job.actorUserId().toString());
        Map<String, Object> resultData = result.data() == null ? Map.of() : result.data();
        if (resultData.get("parsed") instanceof Map<?, ?> directParsed) {
            String rawText = extractor.extract(storage.get(job.objectKey()), job.filename());
            ParsedResume parsed = parsedFromDirect(job.filename(), rawText, directParsed,
                    resultData.get("overall_assessment"), resultData.get("warnings"));
            resultTransaction.executeWithoutResult(tx->{
                CandidateParseJob currentJob=candidateParseJob(resumeFileId,true);
                if(!"PROCESSING".equals(currentJob.status())||!java.util.Objects.equals(currentJob.providerTaskId(),job.providerTaskId()))return;
                Integer current=jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0)+1 FROM resume_parse_versions WHERE candidate_id=?",Integer.class,job.candidateId());
                TenantScope completionScope=new TenantScope(job.tenantId(),job.enterprisePoolSyncEnabled()?"ENTERPRISE":"PERSONAL",null,null,false,job.enterprisePoolSyncEnabled(),false,false);
                saveParsedResume(completionScope,job.candidateId(),job.resumeFileId(),parsed,current==null?1:current);
                if(job.enterprisePoolSyncEnabled())enterprisePools.syncCandidate(completionScope,job.actorUserId(),job.candidateId());
                audit(job.actorUserId(),completionScope,"RESUME_PARSE_COMPLETED",job.candidateId());
                aiPlatform.confirmResultPersisted(job.providerTaskId().toString(), result);
            });
            return;
        }
        if (job.providerTaskId() != null) aiPlatform.holdForReconciliation(job.providerTaskId().toString(),"AI_RESUME_STRUCTURED_RESULT_MISSING");
        resultTransaction.executeWithoutResult(tx->jdbc.update("UPDATE resume_files SET error_code='AI_SCHEMA_INVALID',updated_at=? WHERE id=? AND tenant_id=? AND status='PROCESSING' AND provider_task_id=?",
                timestamp(Instant.now()),job.resumeFileId(),job.tenantId(),job.providerTaskId()));
    }

    public void recordResultMappingFailure(UUID resumeFileId){
        jdbc.query("SELECT provider_task_id FROM resume_files WHERE id=? AND status='PROCESSING'",rs->{String task=rs.getString(1);if(task!=null)aiPlatform.holdForReconciliation(task,"IR_RESULT_MAPPING_FAILED");},resumeFileId);
    }

    @Transactional
    public void completeResumeParseOutbox(UUID eventId) {
        jdbc.update("UPDATE outbox_events SET status='SENT',sent_at=? WHERE id=?", timestamp(Instant.now()), eventId);
    }

    @Transactional
    public void failResumeParseOutbox(ResumeParseOutboxClaim claim, String error) {
        if (claim.attempts() < 3) {
            jdbc.update("UPDATE outbox_events SET status='PENDING',next_attempt_at=? WHERE id=?",
                    timestamp(Instant.now().plusSeconds(claim.attempts())), claim.eventId());
            return;
        }
        CandidateParseJob job = candidateParseJob(claim.resumeFileId(), true);
        failResumeParse(job, "WORKER", error);
        jdbc.update("UPDATE outbox_events SET status='FAILED',sent_at=? WHERE id=?", timestamp(Instant.now()), claim.eventId());
    }

    private CandidateParseJob candidateParseJob(UUID resumeFileId, boolean forUpdate) {
        String lock = forUpdate ? " FOR UPDATE" : "";
        List<CandidateParseJob> jobs = jdbc.query("""
                SELECT rf.id,rf.tenant_id,rf.candidate_id,rf.provider_task_id,rf.status,rf.parse_idempotency_key,rf.enterprise_pool_sync_enabled,
                       rf.created_by,f.object_key,f.original_filename,f.id,f.sha256,f.media_type,f.size_bytes,rf.execution_context::text
                FROM resume_files rf JOIN file_assets f ON f.id=rf.file_asset_id
                WHERE rf.id=?""" + lock, (rs, n) -> new CandidateParseJob(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getObject(8, UUID.class),
                rs.getString(9), pii.decryptIfEncrypted(rs.getString(10)),rs.getObject(11,UUID.class),rs.getString(12),rs.getString(13),rs.getLong(14),rs.getString(15)), resumeFileId);
        if (jobs.isEmpty()) throw notFound();
        return jobs.getFirst();
    }

    private void failResumeParse(CandidateParseJob job, String code, String message) {
        jdbc.update("UPDATE resume_files SET status='FAILED',error_code=?,updated_at=? WHERE id=? AND tenant_id=?",
                code, timestamp(Instant.now()), job.resumeFileId(), job.tenantId());
        audit(job.actorUserId(), new TenantScope(job.tenantId(), job.enterprisePoolSyncEnabled() ? "ENTERPRISE" : "PERSONAL", null, null,
                false, job.enterprisePoolSyncEnabled(), false, false), "RESUME_PARSE_FAILED", job.candidateId());
    }

    @SuppressWarnings("unchecked")
    private ParsedResume parsedFromDirect(String filename, String rawText, Map<?, ?> parsed, Object analysis, Object warningsValue) {
        Map<?, ?> basic = parsed.get("basic_info") instanceof Map<?, ?> value ? value : Map.of();
        Map<?, ?> experience = parsed.get("experience_summary") instanceof Map<?, ?> value ? value : Map.of();
        String name = nullableString(basic.get("name"));
        String email = nullableString(basic.get("email")); String phone = nullableString(basic.get("phone"));
        String headline = nullableString(basic.get("headline"));
        List<String> skills = jsonObjectList(parsed.get("skills"), true);
        List<?> work = objectRows(parsed.get("work_experience"));
        List<?> education = objectRows(parsed.get("education"));
        List<String> warnings = warningsValue instanceof List<?> values ? values.stream().map(value -> {
            if (value instanceof Map<?, ?> map) return String.valueOf(map.containsKey("description") ? map.get("description") : "") + " " + String.valueOf(map.containsKey("verification_question") ? map.get("verification_question") : "");
            return String.valueOf(value);
        }).filter(value -> !value.isBlank()).toList() : List.of();
        java.math.BigDecimal years = decimalValue(experience.get("total_years"));
        String degree = highestDegree(parsed.get("education"));
        return new ParsedResume(name, email == null ? "" : email, phone == null ? "" : phone,
                headline == null ? "" : headline, years, degree, skills, work, education,
                analysis == null ? "" : String.valueOf(analysis), warnings, rawText,
                copyNullableObject(parsed));
    }

    static Map<String,Object> copyNullableObject(Map<?,?> value) {
        Map<String,Object> out=new LinkedHashMap<>();value.forEach((key,item)->{if(key instanceof String name)out.put(name,item);});return out;
    }
    private static List<?> objectRows(Object value){return value instanceof List<?> rows?rows:List.of();}

    private List<String> jsonObjectList(Object value, boolean skillNames) {
        if (!(value instanceof List<?> values)) return List.of();
        LinkedHashMap<String,String> names=new LinkedHashMap<>();
        for(Object item:values){if(item instanceof Map<?,?> map&&map.get("name") instanceof String name&&!name.isBlank())names.putIfAbsent(java.text.Normalizer.normalize(name.trim(),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT),name.trim());}
        return new ArrayList<>(names.values());
    }

    private String highestDegree(Object educationRows) {
        if (!(educationRows instanceof List<?> rows)) return null;
        List<String> degrees = rows.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .map(row -> nullableString(row.get("degree"))).filter(java.util.Objects::nonNull).toList();
        Map<String,List<String>> levels=new LinkedHashMap<>();levels.put("博士",List.of("博士","doctor","phd","ph.d"));levels.put("硕士",List.of("硕士","master"));levels.put("本科",List.of("本科","bachelor"));levels.put("专科",List.of("专科","associate"));levels.put("高中",List.of("高中","high school"));levels.put("中专",List.of("中专"));
        for(var level:levels.entrySet())if(degrees.stream().map(value->value.toLowerCase(Locale.ROOT)).anyMatch(value->level.getValue().stream().anyMatch(value::contains)))return level.getKey();
        return null;
    }
    private static String nullableString(Object value) { return value == null ? null : String.valueOf(value); }
    private static java.math.BigDecimal decimalValue(Object value) { try { return value == null ? null : new java.math.BigDecimal(String.valueOf(value)); } catch (RuntimeException ex) { return null; } }

    private static String filenameDisplayName(String filename) {
        String value = filename.replaceFirst("(?i)\\.(pdf|docx|txt)$", "")
                .replaceAll("(?i)(resume|cv|简历|候选人)", "").replaceAll("[_-]+", " ").trim();
        return value.isBlank() ? "待 AI 识别" : value.substring(0, Math.min(value.length(), 50));
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

    private void audit(UUID actor, TenantScope scope, String action, UUID resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,tenant_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,'CANDIDATE',?,?)
                """, UUID.randomUUID(), actor, scope.tenantId(), action,
                resourceId.toString(), timestamp(Instant.now()));
    }

    private CandidateSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.math.BigDecimal matchScoreValue = rs.getBigDecimal("match_score");
        return new CandidateSummary(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                pii.decrypt(rs.getString("full_name_ciphertext")),
                pii.decrypt(rs.getString("phone_ciphertext")), pii.decrypt(rs.getString("email_ciphertext")), rs.getString("status"),
                rs.getString("parse_status"), pii.decryptIfEncrypted(rs.getString("original_filename")), rs.getString("headline"),
                rs.getBigDecimal("years_experience"), rs.getString("highest_education"), stringsStatic(rs.getString("skills")),
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
     * Deleted or orphaned file_assets keep their row for audit, but the tenant+sha256 unique index
     * must be released so the same resume can be imported again.
     */
    private void releaseHashSlot(UUID tenantId, String hash) {
        List<AssetHashRow> lockedAssets = jdbc.query("""
                SELECT f.id, f.sha256 FROM file_assets f
                WHERE f.tenant_id=? AND f.sha256=?
                FOR UPDATE
                """, (rs, rowNum) -> new AssetHashRow(rs.getObject(1, UUID.class), rs.getString(2)),
                tenantId, hash);
        for (AssetHashRow asset : lockedAssets) {
            if (hasActiveCandidateForAsset(tenantId, asset.id())) continue;
            jdbc.update("""
                    UPDATE file_assets SET sha256=? WHERE id=? AND tenant_id=?
                    """, archivedSha256(asset.id(), asset.sha256()), asset.id(), tenantId);
        }
    }

    private boolean hasActiveCandidateForAsset(UUID tenantId, UUID assetId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM resume_files rf
                JOIN candidates c ON c.id = rf.candidate_id
                WHERE rf.file_asset_id=? AND rf.tenant_id=? AND c.status<>'DELETED'
                """, Integer.class, assetId, tenantId);
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
        if(name==null||name.isBlank())return "待确认";
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

    private List<String> experienceDescriptions(String value){
        try {List<?> rows=objectMapper.readValue(value==null?"[]":value,List.class);return rows.stream().map(row->{if(row instanceof Map<?,?> m)return java.util.List.of("company","position","school","degree","major","description").stream().map(m::get).filter(java.util.Objects::nonNull).map(String::valueOf).collect(java.util.stream.Collectors.joining(" · "));return String.valueOf(row);}).toList();}
        catch(Exception ex){throw new IllegalStateException("结构化经历无法读取",ex);}
    }
    private Map<String,Object> sanitizedStructuredResume(String value){
        if(value==null)return Map.of();
        Map<String,Object> data=parseProfileMap(value);
        if(data.get("ciphertext") instanceof String ciphertext)data=parseProfileMap(pii.decrypt(ciphertext));
        if(data.get("basic_info") instanceof Map<?,?> basic){Map<String,Object> safe=copyNullableObject(basic);safe.put("name",null);safe.put("email",null);safe.put("phone",null);data.put("basic_info",safe);}
        return data;
    }

    static Map<String,Object> mergeParsedProfile(Map<String,Object> parsed,Map<String,Object> existing){
        Map<String,Object> merged=new LinkedHashMap<>(existing);
        if(!Boolean.TRUE.equals(existing.get("manualConfirmed"))) {
            for(var entry:parsed.entrySet())if(!java.util.Set.of("tags","talentStatus","activityLevel").contains(entry.getKey())||!existing.containsKey(entry.getKey()))merged.put(entry.getKey(),entry.getValue());
        }
        return merged;
    }

    private static Map<String, Object> uploadProfile(ParsedResume parsed) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("source", "简历上传");
        profile.put("skills", parsed.skills());
        profile.put("tags", List.of());
        profile.put("talentStatus", "在库");
        profile.put("activityLevel", "中等活跃");
        profile.put("yearsExperience", parsed.yearsExperience());
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

    private static java.math.BigDecimal parseYears(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        if (text.contains("应届")) return java.math.BigDecimal.ZERO;
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text);
        return matcher.find() ? new java.math.BigDecimal(matcher.group(1)) : null;
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

    private static Map<String, Object> profileMap(ManualTalentInput input, List<String> skills, java.math.BigDecimal years) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("gender", nullable(input.gender()));
        profile.put("province", nullable(input.province()));
        profile.put("city", nullable(input.city()));
        profile.put("district", nullable(input.district()));
        profile.put("currentCompany", nullable(input.currentCompany()));
        profile.put("currentTitle", nullable(input.currentTitle()));
        profile.put("currentLevel", nullable(input.currentLevel()));
        profile.put("yearsExperience", years);
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

    private record ParsedResume(String name, String email, String phone, String headline, java.math.BigDecimal yearsExperience,
                                String education, List<String> skills, List<?> workExperience,
                                List<?> educationExperience, String summary, List<String> warnings,
                                String rawText, Map<String,Object> structuredData) { }
    private record AssetReference(UUID id, String objectKey) { }
    private record FileRow(String objectKey, String filename, String mediaType) { }
    private record AssetHashRow(UUID id, String sha256) { }
    private record CandidateParseJob(UUID resumeFileId, UUID tenantId, UUID candidateId,
                                     UUID providerTaskId, String status, String idempotencyKey, boolean enterprisePoolSyncEnabled,
                                     UUID actorUserId, String objectKey, String filename,UUID fileAssetId,String sha256,String mediaType,long sizeBytes,String executionContext) { }
    public record ResumeParseOutboxClaim(UUID eventId, UUID resumeFileId, int attempts) { }

    public record CandidateSummary(UUID id, UUID tenantId, String displayNameMasked, String phone, String email,
                                   String status, String parseStatus, String originalFilename, String headline,
                                   java.math.BigDecimal yearsExperience, String highestEducation, List<String> skills,
                                   Instant createdAt, Instant updatedAt, java.math.BigDecimal matchScore, String matchedJobTitle,
                                   String profileJson) { }
    public record CandidateDetail(UUID id, UUID tenantId, String displayNameMasked, String phone, String email,
                                  String status, UUID currentParseVersionId, UUID resumeFileId, String parseStatus,
                                  String errorCode, String originalFilename, String mediaType, long sizeBytes,
                                  int parseVersion, String headline, java.math.BigDecimal yearsExperience, String highestEducation,
                                  List<String> skills, List<String> workExperience, List<String> educationExperience,
                                  String summary, List<String> warnings, Instant createdAt, Instant updatedAt,
                                  java.math.BigDecimal matchScore, String matchedJobTitle, String profileJson,Map<String,Object> structuredResume) { }
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
