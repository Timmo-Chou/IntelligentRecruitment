package com.intelligentrecruitment.platform.company.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 平台企业管理服务：查询企业列表和详情。
 */
@Service
public class PlatformCompanyService {

    private final JdbcTemplate jdbc;

    public PlatformCompanyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CompanySummary(
            String companyId, String companyName, String shortName,
            String verificationStatus, String managementStatus,
            int memberCount, String createdAt) {}

    public record CompanyDetail(
            String companyId, String legalName, String displayName,
            String creditCodeMasked, String verificationStatus, String managementStatus,
            String ownerUserId, String ownerDisplayName,
            List<MemberSummary> members, List<WorkspaceSummary> workspaces,
            String createdAt) {}

    public record MemberSummary(String userId, String displayName, String role, String status) {}

    public record WorkspaceSummary(String workspaceId, String workspaceName, String status, int memberCount) {}

    public record PagedResult<T>(List<T> items, long total, int page, int pageSize) {}

    public PagedResult<CompanySummary> listCompanies(String search, String status, int page, int pageSize) {
        int offset = (page - 1) * pageSize;

        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> params = new java.util.ArrayList<>();

        if (search != null && !search.isBlank()) {
            where.append(" AND (c.legal_name ILIKE ? OR c.display_name ILIKE ?)");
            String like = "%" + search + "%";
            params.add(like);
            params.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND c.management_status = ?");
            params.add(status);
        }

        String countSql = "SELECT COUNT(*) FROM companies c " + where;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());

        String dataSql = """
                SELECT c.id AS company_id, c.legal_name, c.display_name, c.verification_status,
                       c.management_status, c.created_at,
                       (SELECT COUNT(*) FROM company_memberships cm WHERE cm.company_id = c.id) AS member_count
                FROM companies c
                """ + where + " ORDER BY c.created_at DESC LIMIT ? OFFSET ?";

        params.add(pageSize);
        params.add(offset);

        List<CompanySummary> items = jdbc.query(dataSql, (rs, n) -> new CompanySummary(
                rs.getString("company_id"),
                rs.getString("legal_name"),
                rs.getString("display_name"),
                rs.getString("verification_status"),
                rs.getString("management_status"),
                rs.getInt("member_count"),
                rs.getTimestamp("created_at").toInstant().toString()
        ), params.toArray());

        return new PagedResult<>(items, total != null ? total : 0, page, pageSize);
    }

    public CompanyDetail getCompanyDetail(UUID companyId) {
        var company = jdbc.query(
                "SELECT c.id, c.legal_name, c.display_name, c.credit_code_masked, " +
                "c.verification_status, c.management_status, c.owner_user_id, c.created_at, " +
                "u.display_name AS owner_name " +
                "FROM companies c LEFT JOIN users u ON u.id = c.owner_user_id WHERE c.id = ?",
                (rs, n) -> new Object() {
                    final String id = rs.getString("id");
                    final String legalName = rs.getString("legal_name");
                    final String displayName = rs.getString("display_name");
                    final String creditCodeMasked = rs.getString("credit_code_masked");
                    final String verificationStatus = rs.getString("verification_status");
                    final String managementStatus = rs.getString("management_status");
                    final String ownerUserId = rs.getString("owner_user_id");
                    final String ownerDisplayName = rs.getString("owner_name");
                    final String createdAt = rs.getTimestamp("created_at").toInstant().toString();
                },
                companyId
        );
        if (company.isEmpty()) {
            throw new ApiException("NOT_FOUND", "企业不存在", HttpStatus.NOT_FOUND);
        }
        var c = company.getFirst();

        List<MemberSummary> members = jdbc.query(
                "SELECT cm.user_id, u.display_name, cm.role, cm.status " +
                "FROM company_memberships cm JOIN users u ON u.id = cm.user_id " +
                "WHERE cm.company_id = ?",
                (rs, n) -> new MemberSummary(
                        rs.getString("user_id"), rs.getString("display_name"),
                        rs.getString("role"), rs.getString("status")),
                companyId
        );

        List<WorkspaceSummary> workspaces = jdbc.query(
                "SELECT w.id, w.name, w.status, " +
                "(SELECT COUNT(*) FROM workspace_memberships wm WHERE wm.workspace_id = w.id) AS member_count " +
                "FROM workspaces w WHERE w.company_id = ?",
                (rs, n) -> new WorkspaceSummary(
                        rs.getString("id"), rs.getString("name"),
                        rs.getString("status"), rs.getInt("member_count")),
                companyId
        );

        return new CompanyDetail(c.id, c.legalName, c.displayName, c.creditCodeMasked,
                c.verificationStatus, c.managementStatus, c.ownerUserId, c.ownerDisplayName,
                members, workspaces, c.createdAt);
    }
}