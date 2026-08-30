package com.intelligentrecruitment.jobs.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService.WorkspaceScope;
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
    private final WorkspaceAccessService workspaceAccess;

    public JobService(JdbcTemplate jdbc, ObjectMapper objectMapper, WorkspaceAccessService workspaceAccess) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workspaceAccess = workspaceAccess;
    }

    public JobStats stats(UUID userId, UUID workspaceId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE workspace_id=? AND status<>'ARCHIVED'", Integer.class, workspaceId);
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE workspace_id=? AND status='ACTIVE'", Integer.class, workspaceId);
        Integer closed = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE workspace_id=? AND status='CLOSED'", Integer.class, workspaceId);
        Integer draft = jdbc.queryForObject(
                "SELECT count(*) FROM jobs WHERE workspace_id=? AND status='DRAFT'", Integer.class, workspaceId);
        return new JobStats(value(total), value(active), value(closed), value(draft));
    }

    public JobListResult list(UUID userId, UUID workspaceId, String search, String status, int page, int pageSize) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        int offset = (safePage - 1) * safePageSize;
        StringBuilder where = new StringBuilder("WHERE workspace_id=? AND status<>'ARCHIVED'");
        List<Object> params = new ArrayList<>();
        params.add(workspaceId);
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

    public JobView get(UUID userId, UUID workspaceId, UUID jobId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        return getScoped(workspaceId, jobId);
    }

    @Transactional
    public JobView create(UUID userId, UUID workspaceId, JobInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        JobInput clean = clean(input);
        jdbc.update("""
                INSERT INTO jobs
                (id,company_id,workspace_id,title,company_name,location,description,requirements,skills,
                 experience_level,education,job_type,status,source,talent_profile,warnings,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'DRAFT','MANUAL','', '[]'::jsonb,?,?,?)
                """, jobId, scope.companyId(), workspaceId, clean.title(), clean.companyName(), clean.location(),
                clean.description(), clean.requirements(), clean.skills(), clean.experienceLevel(), clean.education(),
                clean.jobType(), userId, timestamp(now), timestamp(now));
        UUID versionId = saveSnapshot(scope, jobId, 1, clean, "手工创建职位", userId, now, null);
        jdbc.update("UPDATE jobs SET current_version_id=? WHERE id=?", versionId, jobId);
        audit(userId, scope, "JOB_CREATED", jobId);
        return getScoped(workspaceId, jobId);
    }

    @Transactional
    public JobView createFromConfirmedJd(UUID userId, UUID workspaceId, UUID recruitmentTaskId, UUID jdDraftId, UUID sourceAiRunId,
                                         JobInput input, String talentProfile, String warningsJson) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        List<UUID> existing = jdbc.query("""
                SELECT id FROM jobs WHERE jd_draft_id=? AND workspace_id=? AND status<>'ARCHIVED'
                """, (rs, n) -> rs.getObject("id", UUID.class), jdDraftId, workspaceId);
        if (!existing.isEmpty()) return update(userId, workspaceId, existing.getFirst(), input);
        JobInput clean = clean(input);
        UUID jobId = UUID.randomUUID();
        Instant now = Instant.now();
        String cleanProfile = optional(talentProfile, 10_000);
        String safeWarnings = warningsJson == null || warningsJson.isBlank() ? "[]" : warningsJson;
        jdbc.update("""
                INSERT INTO jobs
                (id,company_id,workspace_id,title,company_name,location,description,requirements,skills,
                 experience_level,education,job_type,status,source,recruitment_task_id,jd_draft_id,talent_profile,warnings,
                 created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'ACTIVE','AI_GENERATED',?,?,?,?::jsonb,?,?,?)
                """, jobId, scope.companyId(), workspaceId, clean.title(), clean.companyName(), clean.location(),
                clean.description(), clean.requirements(), clean.skills(), clean.experienceLevel(), clean.education(),
                clean.jobType(), recruitmentTaskId, jdDraftId, cleanProfile, safeWarnings, userId, timestamp(now), timestamp(now));
        ConfirmedJdSnapshot snapshot = new ConfirmedJdSnapshot(clean, cleanProfile, safeWarnings);
        UUID versionId = saveSnapshot(scope, jobId, 1, snapshot, "确认 AI JD 草稿", userId, now, sourceAiRunId);
        jdbc.update("UPDATE jobs SET current_version_id=? WHERE id=?", versionId, jobId);
        audit(userId, scope, "AI_JD_CONFIRMED", jobId);
        return getScoped(workspaceId, jobId);
    }

    @Transactional
    public JobView update(UUID userId, UUID workspaceId, UUID jobId, JobInput input) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        JobView existing = getScoped(workspaceId, jobId);
        JobInput clean = clean(input);
        Instant now = Instant.now();
        int updated = jdbc.update("""
                UPDATE jobs SET title=?,company_name=?,location=?,description=?,requirements=?,skills=?,
                  experience_level=?,education=?,job_type=?,lock_version=lock_version+1,updated_at=?
                WHERE id=? AND workspace_id=? AND lock_version=? AND status<>'ARCHIVED'
                """, clean.title(), clean.companyName(), clean.location(), clean.description(), clean.requirements(),
                clean.skills(), clean.experienceLevel(), clean.education(), clean.jobType(), timestamp(now), jobId,
                workspaceId, existing.lockVersion());
        if (updated == 0) {
            throw new ApiException("JOB_VERSION_CONFLICT", "职位已被其他成员修改，请刷新后重试", HttpStatus.CONFLICT);
        }
        UUID versionId = saveSnapshot(scope, jobId, nextVersion(jobId), clean, "更新职位", userId, now, null);
        jdbc.update("UPDATE jobs SET current_version_id=? WHERE id=?", versionId, jobId);
        audit(userId, scope, "JOB_UPDATED", jobId);
        return getScoped(workspaceId, jobId);
    }

    /** Keeps the published job and its immutable version history aligned with an edited AI JD. */
    @Transactional
    public void updateFromRecruitmentJd(UUID userId, UUID workspaceId, UUID jdDraftId, JobInput input) {
        List<UUID> jobIds = jdbc.query("""
                SELECT id FROM jobs WHERE jd_draft_id=? AND workspace_id=? AND status<>'ARCHIVED'
                """, (rs, n) -> rs.getObject("id", UUID.class), jdDraftId, workspaceId);
        if (!jobIds.isEmpty()) update(userId, workspaceId, jobIds.getFirst(), input);
    }

    @Transactional
    public JobView updateStatus(UUID userId, UUID workspaceId, UUID jobId, String status) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        String normalized = normalizedStatus(status);
        int updated = jdbc.update("""
                UPDATE jobs SET status=?,lock_version=lock_version+1,updated_at=?
                WHERE id=? AND workspace_id=? AND status<>'ARCHIVED'
                """, normalized, timestamp(Instant.now()), jobId, workspaceId);
        if (updated == 0) throw notFound();
        audit(userId, scope, "JOB_STATUS_CHANGED", jobId);
        return getScoped(workspaceId, jobId);
    }

    @Transactional
    public void delete(UUID userId, UUID workspaceId, UUID jobId) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        int updated = jdbc.update("""
                UPDATE jobs SET status='ARCHIVED',lock_version=lock_version+1,updated_at=?
                WHERE id=? AND workspace_id=? AND status<>'ARCHIVED'
                """, timestamp(Instant.now()), jobId, workspaceId);
        if (updated == 0) throw notFound();
        audit(userId, scope, "JOB_ARCHIVED", jobId);
    }

    @Transactional
    public void batchUpdateStatus(UUID userId, UUID workspaceId, List<UUID> jobIds, String status) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        String normalized = normalizedStatus(status);
        for (UUID jobId : safeIds(jobIds)) {
            jdbc.update("""
                    UPDATE jobs SET status=?,lock_version=lock_version+1,updated_at=?
                    WHERE id=? AND workspace_id=? AND status<>'ARCHIVED'
                    """, normalized, timestamp(Instant.now()), jobId, workspaceId);
        }
        audit(userId, scope, "JOBS_BATCH_STATUS_CHANGED", workspaceId);
    }

    @Transactional
    public void batchDelete(UUID userId, UUID workspaceId, List<UUID> jobIds) {
        WorkspaceScope scope = workspaceAccess.requireBusinessAccess(userId, workspaceId);
        for (UUID jobId : safeIds(jobIds)) {
            jdbc.update("""
                    UPDATE jobs SET status='ARCHIVED',lock_version=lock_version+1,updated_at=?
                    WHERE id=? AND workspace_id=? AND status<>'ARCHIVED'
                    """, timestamp(Instant.now()), jobId, workspaceId);
        }
        audit(userId, scope, "JOBS_BATCH_ARCHIVED", workspaceId);
    }

    public List<JobVersionView> versions(UUID userId, UUID workspaceId, UUID jobId) {
        workspaceAccess.requireBusinessAccess(userId, workspaceId);
        getScoped(workspaceId, jobId);
        return jdbc.query("""
                SELECT id,job_id,version_number,status,snapshot,change_summary,created_by,confirmed_at,created_at
                FROM job_versions WHERE job_id=? AND workspace_id=? ORDER BY version_number DESC
                """, (rs, n) -> new JobVersionView(rs.getObject("id", UUID.class),
                rs.getObject("job_id", UUID.class), rs.getInt("version_number"), rs.getString("status"),
                rs.getString("snapshot"), rs.getString("change_summary"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()), jobId, workspaceId);
    }

    private JobView getScoped(UUID workspaceId, UUID jobId) {
        List<JobView> rows = jdbc.query(jobSelect() + " WHERE id=? AND workspace_id=? AND status<>'ARCHIVED'",
                (rs, n) -> job(rs), jobId, workspaceId);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    private UUID saveSnapshot(WorkspaceScope scope, UUID jobId, int version, Object input, String summary,
                              UUID userId, Instant now, UUID sourceAiRunId) {
        try {
            UUID versionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO job_versions
                    (id,company_id,workspace_id,job_id,version_number,status,snapshot,change_summary,
                     source_ai_run_id,created_by,confirmed_at,created_at)
                    VALUES (?,?,?,?,?,'CONFIRMED',?::jsonb,?,?,?,?,?)
                    """, versionId, scope.companyId(), scope.workspaceId(), jobId, version,
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

    private void audit(UUID actor, WorkspaceScope scope, String action, UUID resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs
                (id,actor_user_id,company_id,workspace_id,action,resource_type,resource_id,created_at)
                VALUES (?,?,?,?,?,'JOB',?,?)
                """, UUID.randomUUID(), actor, scope.companyId(), scope.workspaceId(), action,
                resourceId.toString(), timestamp(Instant.now()));
    }

    private static String jobSelect() {
        return """
                SELECT id,company_id,workspace_id,title,company_name,location,description,requirements,skills,
                       experience_level,education,job_type,status,source,current_version_id,lock_version,
                       talent_profile,warnings::text,created_by,created_at,updated_at FROM jobs
                """;
    }

    private static JobView job(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobView(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getString("title"), rs.getString("company_name"),
                rs.getString("location"), rs.getString("description"), rs.getString("requirements"),
                rs.getString("skills"), rs.getString("experience_level"), rs.getString("education"),
                rs.getString("job_type"), rs.getString("status"), rs.getString("source"),
                rs.getObject("current_version_id", UUID.class), rs.getLong("lock_version"),
                rs.getString("talent_profile"), rs.getString("warnings"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static JobInput clean(JobInput input) {
        if (input == null) throw new ApiException("VALIDATION_FAILED", "职位内容不能为空", HttpStatus.BAD_REQUEST);
        return new JobInput(required(input.title(), "职位名称不能为空", 200),
                required(input.companyName(), "企业名称不能为空", 200), optional(input.location(), 200),
                optional(input.description(), 20_000), optional(input.requirements(), 20_000),
                optional(input.skills(), 4_000), optional(input.experienceLevel(), 80),
                optional(input.education(), 80), defaulted(input.jobType(), "全职", 50));
    }

    private static String normalizedStatus(String status) {
        String value = required(status, "状态不能为空", 32).toUpperCase();
        if (!List.of("DRAFT", "ACTIVE", "CLOSED").contains(value)) {
            throw new ApiException("INVALID_STATUS", "无效的职位状态", HttpStatus.BAD_REQUEST);
        }
        return value;
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

    public record JobInput(String title, String companyName, String location, String description,
                           String requirements, String skills, String experienceLevel,
                           String education, String jobType) {
    }

    public record JobView(UUID id, UUID companyId, UUID workspaceId, String title, String companyName,
                          String location, String description, String requirements, String skills,
                          String experienceLevel, String education, String jobType, String status, String source,
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
