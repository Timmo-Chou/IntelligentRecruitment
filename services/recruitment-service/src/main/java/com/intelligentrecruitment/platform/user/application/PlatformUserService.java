package com.intelligentrecruitment.platform.user.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 平台用户管理服务：查询注册用户列表和详情。
 */
@Service
public class PlatformUserService {

    private final JdbcTemplate jdbc;

    public PlatformUserService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserSummary(
            String userId, String displayName, String phone, String status,
            String verificationStatus, String createdAt) {}

    public record UserDetail(
            String userId, String displayName, String phone, String status,
            String verificationStatus, String realNameMasked,
            String identityHash, String reviewedBy, String reviewedAt, String rejectionReason,
            List<CompanyMembership> companies, List<WorkspaceMembership> workspaces,
            String createdAt) {}

    public record CompanyMembership(String companyId, String companyName, String role, String status) {}

    public record WorkspaceMembership(String workspaceId, String workspaceName, String role, String status) {}

    public record PagedResult<T>(List<T> items, long total, int page, int pageSize) {}

    public PagedResult<UserSummary> listUsers(String search, String status, int page, int pageSize) {
        int offset = (page - 1) * pageSize;

        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();

        if (search != null && !search.isBlank()) {
            where.append(" AND (u.display_name ILIKE ? OR u.id::text ILIKE ?)");
            String like = "%" + search + "%";
            params.add(like);
            params.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND u.status = ?");
            params.add(status);
        }

        String countSql = "SELECT COUNT(*) FROM users u " + where;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());

        String dataSql = """
                SELECT u.id AS user_id, u.display_name, u.phone_last_four, u.status, u.created_at,
                       COALESCE(pi.verification_status, 'UNVERIFIED') AS verification_status
                FROM users u
                LEFT JOIN personal_identities pi ON pi.user_id = u.id
                """ + where + " ORDER BY u.created_at DESC LIMIT ? OFFSET ?";

        params.add(pageSize);
        params.add(offset);

        List<UserSummary> items = jdbc.query(dataSql, (rs, n) -> new UserSummary(
                rs.getString("user_id"),
                rs.getString("display_name"),
                rs.getString("phone_last_four"),
                rs.getString("status"),
                rs.getString("verification_status"),
                rs.getTimestamp("created_at").toInstant().toString()
        ), params.toArray());

        return new PagedResult<>(items, total != null ? total : 0, page, pageSize);
    }

    public UserDetail getUserDetail(UUID userId) {
        // 查询用户基本信息
        var user = jdbc.query(
                "SELECT u.id, u.display_name, u.phone_last_four, u.status, u.created_at, " +
                "COALESCE(pi.verification_status, 'UNVERIFIED') AS verification_status, " +
                "pi.real_name_masked, pi.identity_hash, pi.reviewed_by, pi.reviewed_at, pi.rejection_reason " +
                "FROM users u LEFT JOIN personal_identities pi ON pi.user_id = u.id WHERE u.id = ?",
                (rs, n) -> new Object() {
                    final String id = rs.getString("id");
                    final String displayName = rs.getString("display_name");
                    final String phone = rs.getString("phone_last_four");
                    final String status = rs.getString("status");
                    final String verificationStatus = rs.getString("verification_status");
                    final String realNameMasked = rs.getString("real_name_masked");
                    final String identityHash = rs.getString("identity_hash");
                    final String reviewedBy = rs.getString("reviewed_by");
                    final String reviewedAt = rs.getTimestamp("reviewed_at") != null
                            ? rs.getTimestamp("reviewed_at").toInstant().toString() : null;
                    final String rejectionReason = rs.getString("rejection_reason");
                    final String createdAt = rs.getTimestamp("created_at").toInstant().toString();
                },
                userId
        );
        if (user.isEmpty()) {
            throw new ApiException("NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        }
        var u = user.getFirst();

        // 查询用户所属企业
        List<CompanyMembership> companies = jdbc.query(
                "SELECT c.id, c.display_name, cm.role, cm.status " +
                "FROM company_memberships cm JOIN companies c ON c.id = cm.company_id " +
                "WHERE cm.user_id = ?",
                (rs, n) -> new CompanyMembership(
                        rs.getString("id"), rs.getString("display_name"),
                        rs.getString("role"), rs.getString("status")),
                userId
        );

        // 查询用户工作空间
        List<WorkspaceMembership> workspaces = jdbc.query(
                "SELECT w.id, w.name, wm.role, wm.status " +
                "FROM workspace_memberships wm JOIN workspaces w ON w.id = wm.workspace_id " +
                "WHERE wm.user_id = ?",
                (rs, n) -> new WorkspaceMembership(
                        rs.getString("id"), rs.getString("name"),
                        rs.getString("role"), rs.getString("status")),
                userId
        );

        return new UserDetail(u.id, u.displayName, u.phone, u.status, u.verificationStatus,
                u.realNameMasked, u.identityHash, u.reviewedBy, u.reviewedAt, u.rejectionReason,
                companies, workspaces, u.createdAt);
    }

    public void disableUser(UUID userId) {
        int updated = jdbc.update("UPDATE users SET status = 'DISABLED', updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now()), userId);
        if (updated == 0) {
            throw new ApiException("NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        }
    }

    public void enableUser(UUID userId) {
        int updated = jdbc.update("UPDATE users SET status = 'ACTIVE', updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now()), userId);
        if (updated == 0) {
            throw new ApiException("NOT_FOUND", "用户不存在", HttpStatus.NOT_FOUND);
        }
    }
}