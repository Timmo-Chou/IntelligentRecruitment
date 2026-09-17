package com.intelligentrecruitment.jobs.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.pools.application.EnterprisePoolService;
import com.intelligentrecruitment.tenancy.application.TenantAccessService;
import com.intelligentrecruitment.tenancy.application.TenantAccessService.TenantScope;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class JobService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TenantAccessService tenantAccess;
    private final EnterprisePoolService enterprisePools;

    public JobService(JdbcTemplate jdbc, ObjectMapper objectMapper, TenantAccessService tenantAccess, EnterprisePoolService enterprisePools) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tenantAccess = tenantAccess;
        this.enterprisePools = enterprisePools;
    }

    public JobStats stats(UUID userId, UUID tenantId) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        String owner = scope.enterprise() ? " AND created_by=?" : "";
        Object[] p = scope.enterprise() ? new Object[]{tenantId, userId} : new Object[]{tenantId};
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE tenant_id=? AND status<>'ARCHIVED'" + owner, Integer.class, p);
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE tenant_id=? AND status='ACTIVE'" + owner, Integer.class, p);
        Integer closed = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE tenant_id=? AND status='CLOSED'" + owner, Integer.class, p);
        Integer draft = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE tenant_id=? AND status='DRAFT'" + owner, Integer.class, p);
        return new JobStats(value(total), value(active), value(closed), value(draft));
    }

    public JobListResult list(UUID userId, UUID tenantId, String search, String status, int page, int pageSize) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        int offset = (safePage - 1) * safePageSize;
        StringBuilder where = new StringBuilder("WHERE tenant_id=? AND status<>'ARCHIVED'");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (scope.enterprise()) { where.append(" AND created_by=?"); params.add(userId); }
        if (search != null && !search.isBlank()) {
            where.append(" AND (title ILIKE ? OR company_name ILIKE ? OR location ILIKE ? OR skills ILIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status=?");
            params.add(normalizedStatus(status));
        }
        Integer total = jdbc.queryForObject("SELECT count(*) FROM jobs " + where, Integer.class, params.toArray());
        List<JobView> items = jdbc.query(jobSelect() + " " + where + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                (rs, n) -> job(rs), concat(params, safePageSize, offset));
        return new JobListResult(items, value(total), safePage, safePageSize);
    }

    public JobView get(UUID userId, UUID tenantId, UUID jobId) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        requireSourceOwner(scope, userId, jobId);
        return getScoped(tenantId, jobId);
    }

    @Transactional
    public JobView create(UUID userId, UUID tenantId, JobInput input) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        JobInput clean = clean(input);
        jdbc.update("""
                INSERT INTO jobs
                (id,tenant_id,title,company_name,location,salary_range,description,requirements,skills,
                 experience_level,education,job_type,nice_to_haves,benefits,status,source,talent_profile,warnings,created_by,created_at,updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT','MANUAL','', '[]'::jsonb, ?, ?, ?)
                """, jobId, scope.tenantId(), clean.title(), clean.companyName(), clean.location(),
                clean.salaryRange(), clean.description(), clean.requirements(), clean.skills(), clean.experienceLevel(), clean.education(),
                clean.jobType(), clean.niceToHaves(), clean.benefits(), userId, timestamp(now), timestamp(now));
        UUID versionId = saveSnapshot(scope, jobId, 1, clean, "手工创建职位", userId, now, null);
        jdbc.update("UPDATE jobs SET current_version_id=? WHERE id=?", versionId, jobId);
        audit(userId, scope, "JOB_CREATED", jobId);
        JobView detail=getScoped(tenantId, jobId); enterprisePools.syncJob(scope,userId,jobId); return detail;
    }

    @Transactional
    public JobView createFromConfirmedJd(UUID userId, UUID tenantId, UUID recruitmentTaskId, UUID jdDraftId, UUID sourceAiRunId,
                                         JobInput input, String talentProfile, String warningsJson) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        JobInput clean = clean(input);
        String cleanProfile = optional(talentProfile, 10_000);
        String safeWarnings = warningsJson == null || warningsJson.isBlank() ? "[]" : warningsJson;
        List<UUID> existing = jdbc.query("""
                SELECT id FROM jobs WHERE jd_draft_id=? AND tenant_id=? AND status<>'ARCHIVED'
                """, (rs, n) -> rs.getObject("id", UUID.class), jdDraftId, tenantId);
        if (!existing.isEmpty()) {
            JobView updated = update(userId, tenantId, existing.getFirst(), clean);
            jdbc.update("UPDATE jobs SET talent_profile=?,warnings=?::jsonb,updated_at=? WHERE id=? AND tenant_id=?",
                    cleanProfile, safeWarnings, timestamp(Instant.now()), updated.id(), tenantId);
            return getScoped(tenantId, updated.id());
        }
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO jobs
                (id,tenant_id,title,company_name,location,salary_range,description,requirements,skills,
                 experience_level,education,job_type,nice_to_haves,benefits,status,source,recruitment_task_id,jd_draft_id,talent_profile,warnings,
                 created_by,created_at,updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT','AI_GENERATED', ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, jobId, scope.tenantId(), clean.title(), clean.companyName(), clean.location(),
                clean.salaryRange(), clean.description(), clean.requirements(), clean.skills(), clean.experienceLevel(), clean.education(),
                clean.jobType(), clean.niceToHaves(), clean.benefits(), recruitmentTaskId, jdDraftId, cleanProfile, safeWarnings, userId, timestamp(now), timestamp(now));
        ConfirmedJdSnapshot snapshot = new ConfirmedJdSnapshot(clean, cleanProfile, safeWarnings);
        UUID versionId = saveSnapshot(scope, jobId, 1, snapshot, "确认 AI JD 草稿", userId, now, sourceAiRunId);
        jdbc.update("UPDATE jobs SET current_version_id=? WHERE id=?", versionId, jobId);
        audit(userId, scope, "AI_JD_CONFIRMED", jobId);
        JobView detail=getScoped(tenantId, jobId); enterprisePools.syncJob(scope,userId,jobId); return detail;
    }

    @Transactional
    public JobView update(UUID userId, UUID tenantId, UUID jobId, JobInput input) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        JobView existing = getScoped(tenantId, jobId);
        requireSourceOwner(scope, userId, jobId);
        JobInput clean = clean(input);
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE jobs SET title=?,company_name=?,location=?,salary_range=?,description=?,requirements=?,skills=?,
                  experience_level=?,education=?,job_type=?,nice_to_haves=?,benefits=?,lock_version=lock_version+1,updated_at=?
                WHERE id=? AND tenant_id=? AND lock_version=? AND status<>'ARCHIVED'
                """, clean.title(), clean.companyName(), clean.location(), clean.salaryRange(), clean.description(), clean.requirements(),
                clean.skills(), clean.experienceLevel(), clean.education(), clean.jobType(), clean.niceToHaves(), clean.benefits(), timestamp(now), jobId,
                tenantId, existing.lockVersion());
        if (updated == 0) {
            throw new ApiException("JOB_VERSION_CONFLICT", "职位已被其他成员修改，请刷新后重试", HttpStatus.CONFLICT);
        }
        UUID versionId = saveSnapshot(scope, jobId, nextVersion(jobId), clean, "更新职位", userId, now, null);
        jdbc.update("UPDATE jobs SET current_version_id=? WHERE id=?", versionId, jobId);
        audit(userId, scope, "JOB_UPDATED", jobId);
        enterprisePools.syncJob(scope, userId, jobId);
        return getScoped(tenantId, jobId);
    }

    /** Keeps the published job and its immutable version history aligned with an edited AI JD. */
    @Transactional
    public void updateFromRecruitmentJd(UUID userId, UUID tenantId, UUID jdDraftId, JobInput input) {
        List<UUID> jobIds = jdbc.query("""
                SELECT id FROM jobs WHERE jd_draft_id=? AND tenant_id=? AND status<>'ARCHIVED'
                """, (rs, n) -> rs.getObject("id", UUID.class), jdDraftId, tenantId);
        if (!jobIds.isEmpty()) update(userId, tenantId, jobIds.getFirst(), input);
    }

    @Transactional
    public JobView updateStatus(UUID userId, UUID tenantId, UUID jobId, String status) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        String normalized = normalizedStatus(status);
        requireSourceOwner(scope, userId, jobId);
        JobView existing = getScoped(tenantId, jobId);
        if ("ACTIVE".equals(normalized)) requireReadyForPublication(existing);
        int updated = jdbc.update("""
                UPDATE jobs SET status=?,lock_version=lock_version+1,updated_at=?
                WHERE id=? AND tenant_id=? AND status<>'ARCHIVED'
                """, normalized, timestamp(Instant.now()), jobId, tenantId);
        if (updated == 0) throw notFound();
        audit(userId, scope, "JOB_STATUS_CHANGED", jobId);
        enterprisePools.syncJob(scope, userId, jobId);
        return getScoped(tenantId, jobId);
    }

    @Transactional
    public void delete(UUID userId, UUID tenantId, UUID jobId) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        requireSourceOwner(scope, userId, jobId);
        int updated = jdbc.update("""
                UPDATE jobs SET status='ARCHIVED',lock_version=lock_version+1,updated_at=?
                WHERE id=? AND tenant_id=? AND status<>'ARCHIVED'
                """, timestamp(Instant.now()), jobId, tenantId);
        if (updated == 0) throw notFound();
        audit(userId, scope, "JOB_ARCHIVED", jobId);
    }

    @Transactional
    public void batchUpdateStatus(UUID userId, UUID tenantId, List<UUID> jobIds, String status) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        String normalized = normalizedStatus(status);
        for (UUID jobId : safeIds(jobIds)) {
            requireSourceOwner(scope, userId, jobId);
            jdbc.update("""
                    UPDATE jobs SET status=?,lock_version=lock_version+1,updated_at=?
                    WHERE id=? AND tenant_id=? AND status<>'ARCHIVED'
                    """, normalized, timestamp(Instant.now()), jobId, tenantId);
            enterprisePools.syncJob(scope, userId, jobId);
        }
        audit(userId, scope, "JOBS_BATCH_STATUS_CHANGED", tenantId);
    }

    @Transactional
    public void batchDelete(UUID userId, UUID tenantId, List<UUID> jobIds) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        for (UUID jobId : safeIds(jobIds)) {
            requireSourceOwner(scope, userId, jobId);
            jdbc.update("""
                    UPDATE jobs SET status='ARCHIVED',lock_version=lock_version+1,updated_at=?
                    WHERE id=? AND tenant_id=? AND status<>'ARCHIVED'
                    """, timestamp(Instant.now()), jobId, tenantId);
        }
        audit(userId, scope, "JOBS_BATCH_ARCHIVED", tenantId);
    }

    public List<JobVersionView> versions(UUID userId, UUID tenantId, UUID jobId) {
        TenantScope scope = tenantAccess.requireBusinessAccess(userId, tenantId);
        requireSourceOwner(scope, userId, jobId);
        getScoped(tenantId, jobId);
        return jdbc.query("""
                SELECT id,job_id,version_number,status,snapshot,change_summary,created_by,confirmed_at,created_at
                FROM job_versions WHERE job_id=? AND tenant_id=? ORDER BY version_number DESC
                """, (rs, n) -> new JobVersionView(rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getInt("version_number"), rs.getString("status"),
                rs.getString("snapshot"), rs.getString("change_summary"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()), jobId, tenantId);
    }

    private JobView getScoped(UUID tenantId, UUID jobId) {
        List<JobView> rows = jdbc.query(jobSelect() + " WHERE id=? AND tenant_id=? AND status<>'ARCHIVED'",
                (rs, n) -> job(rs), jobId, tenantId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private void requireSourceOwner(TenantScope scope, UUID userId, UUID jobId) {
        if (!scope.enterprise()) return;
        Integer count = jdbc.queryForObject("SELECT count(*) FROM jobs WHERE id=? AND tenant_id=? AND created_by=? AND status<>'ARCHIVED'",
                Integer.class, jobId, scope.tenantId(), userId);
        if (count == null || count == 0) throw new ApiException("JOB_NOT_FOUND", "职位不存在或不属于当前账号", HttpStatus.NOT_FOUND);
    }

    private UUID saveSnapshot(TenantScope scope, UUID jobId, int version, Object input, String summary,
                              UUID userId, Instant now, UUID sourceAiRunId) {
        try {
            UUID versionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO job_versions
                    (id,tenant_id,job_id,version_number,status,snapshot,change_summary,
                     source_ai_run_id,created_by,confirmed_at,created_at)
                    VALUES (?,?,?,?,'CONFIRMED',?::jsonb,?,?,?,?,?)
                    """, versionId, scope.tenantId(), jobId, version,
                    objectMapper.writeValueAsString(input), summary, sourceAiRunId, userId,
                    timestamp(now), timestamp(now));
            return versionId;
        } catch (JsonProcessingException exception) {
            throw new ApiException("SNAPSHOT_FAILED", "保存职位版本失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private int nextVersion(UUID jobId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(version_number),0) FROM job_versions WHERE job_id=?",
                Integer.class, jobId);
        return value(max) + 1;
    }

    private void audit(UUID actor, TenantScope scope, String action, UUID resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,tenant_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,?,'JOB',?,?)
                """, UUID.randomUUID(), actor, scope.tenantId(), action,
                resourceId.toString(), timestamp(Instant.now()));
    }

    private static String jobSelect() {
        return """
                SELECT id,tenant_id,title,company_name,location,salary_range,description,requirements,skills,
                       experience_level,education,job_type,nice_to_haves,benefits,status,source,current_version_id,lock_version,
                       talent_profile,warnings::text,created_by,created_at,updated_at FROM jobs
                """;
    }

    private static JobView job(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobView(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("title"), rs.getString("company_name"),
                rs.getString("location"), rs.getString("salary_range"), rs.getString("description"), rs.getString("requirements"),
                rs.getString("skills"), rs.getString("experience_level"), rs.getString("education"),
                rs.getString("job_type"), rs.getString("nice_to_haves"), rs.getString("benefits"), rs.getString("status"), rs.getString("source"),
                rs.getObject("current_version_id", UUID.class), rs.getLong("lock_version"),
                rs.getString("talent_profile"), rs.getString("warnings"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static JobInput clean(JobInput input) {
        if (input == null) throw new ApiException("VALIDATION_FAILED", "职位内容不能为空", HttpStatus.BAD_REQUEST);
        return new JobInput(required(input.title(), "职位名称不能为空", 200),
                required(input.companyName(), "企业名称不能为空", 200), optional(input.location(), 200), optional(input.salaryRange(), 200),
                optional(input.description(), 20_000), optional(input.requirements(), 20_000),
                optional(input.skills(), 4_000), optional(input.experienceLevel(), 80),
                optional(input.education(), 80), defaulted(input.jobType(), "全职", 50),
                optional(input.niceToHaves(), 10_000), optional(input.benefits(), 10_000));
    }

    private static String normalizedStatus(String status) {
        String value = required(status, "状态不能为空", 32).toUpperCase();
        if (!List.of("DRAFT", "ACTIVE", "CLOSED").contains(value)) {
            throw new ApiException("INVALID_STATUS", "无效的职位状态", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private static void requireReadyForPublication(JobView job) {
        List<String> missing = new ArrayList<>();
        if (unconfirmed(job.location())) missing.add("工作地点");
        if (unconfirmed(job.salaryRange())) missing.add("薪资范围");
        if (unconfirmed(job.description())) missing.add("岗位职责");
        if (unconfirmed(job.requirements())) missing.add("任职要求");
        if (unconfirmed(job.skills())) missing.add("关键技能");
        if (!missing.isEmpty()) {
            throw new ApiException("JOB_NOT_READY_TO_PUBLISH", "发布前请补全：" + String.join("、", missing), HttpStatus.CONFLICT);
        }
        if (job.warnings() != null && !job.warnings().isBlank() && !"[]".equals(job.warnings().trim())) {
            throw new ApiException("JOB_NOT_READY_TO_PUBLISH", "发布前请处理职位待确认项", HttpStatus.CONFLICT);
        }
    }

    private static boolean unconfirmed(String value) {
        return value == null || value.isBlank() || value.contains("待确认");
    }

    private static List<UUID> safeIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100) {
            throw new ApiException("INVALID_JOB_IDS", "请选择1至100个职位", HttpStatus.BAD_REQUEST);
        }
        return ids.stream().distinct().toList();
    }

    private static String required(String value, String message, int max) {
        if (value == null || value.isBlank()) throw new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
        String clean = value.trim();
        if (clean.length() > max) throw new ApiException("VALIDATION_FAILED", message + "且不能超过" + max + "字", HttpStatus.BAD_REQUEST);
        return clean;
    }

    private static String optional(String value, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > max) throw new ApiException("VALIDATION_FAILED", "字段内容过长", HttpStatus.BAD_REQUEST);
        return clean;
    }

    private static String defaulted(String value, String fallback, int max) {
        return optional(value == null || value.isBlank() ? fallback : value, max);
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static Object[] concat(List<Object> params, Object... extra) {
        Object[] values = new Object[params.size() + extra.length];
        for (int index = 0; index < params.size(); index++) values[index] = params.get(index);
        for (int index = 0; index < extra.length; index++) values[params.size() + index] = extra[index];
        return values;
    }

    private static ApiException notFound() {
        return new ApiException("JOB_NOT_FOUND", "职位不存在", HttpStatus.NOT_FOUND);
    }

    public record JobInput(String title, String companyName, String location, String salaryRange, String description,
                           String requirements, String skills, String experienceLevel,
                           String education, String jobType, String niceToHaves, String benefits) {
    }

    public record JobView(UUID id, UUID tenantId, String title, String companyName,
                          String location, String salaryRange, String description, String requirements, String skills,
                          String experienceLevel, String education, String jobType, String niceToHaves, String benefits, String status, String source,
                          UUID currentVersionId, long lockVersion, String talentProfile, String warnings,
                          UUID createdBy, Instant createdAt, Instant updatedAt) {
    }

    public record JobListResult(List<JobView> items, int total, int page, int pageSize) {
    }

    public record JobStats(int total, int active, int closed, int draft) {
    }

    public record JobVersionView(UUID id, UUID jobId, int versionNumber, String status, String snapshot,
                                 String changeSummary, UUID createdBy, Instant confirmedAt, Instant createdAt) {
    }

    public record BatchStatusRequest(List<UUID> jobIds, String status) {
    }

    public record BatchDeleteRequest(List<UUID> jobIds) {
    }

    private record ConfirmedJdSnapshot(JobInput job, String talentProfile, String warningsJson) {
    }
}
