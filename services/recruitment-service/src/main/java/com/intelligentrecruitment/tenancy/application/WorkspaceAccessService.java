package com.intelligentrecruitment.tenancy.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class WorkspaceAccessService {

    private final JdbcTemplate jdbc;

    public WorkspaceAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WorkspaceScope requireBusinessAccess(UUID userId, UUID workspaceId) {
        List<WorkspaceScope> scopes = jdbc.query("""
                SELECT w.id, w.company_id, w.type, w.name, wm.role
                FROM workspaces w
                JOIN workspace_memberships wm ON wm.workspace_id = w.id
                WHERE w.id = ? AND w.status = 'ACTIVE'
                  AND wm.user_id = ? AND wm.status = 'ACTIVE'
                """, (rs, rowNum) -> new WorkspaceScope(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("type"),
                rs.getString("name"),
                rs.getString("role")), workspaceId, userId);
        if (scopes.isEmpty()) {
            throw new ApiException("WORKSPACE_NOT_FOUND", "工作空间不存在或无权访问", HttpStatus.NOT_FOUND);
        }
        return scopes.getFirst();
    }

    public record WorkspaceScope(UUID workspaceId, UUID companyId, String type, String name, String role) {
    }
}
