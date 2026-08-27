package com.intelligentrecruitment.platform.menu.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

/**
 * 平台菜单管理服务。
 * 负责菜单树的CRUD操作，支持按角色过滤可见性。
 */
@Service
public class MenuService {

    private final JdbcTemplate jdbc;

    public MenuService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取完整菜单树（所有菜单，按 sort_order 排序后构建树形结构）。
     */
    public List<MenuNode> getMenuTree() {
        List<MenuRow> allMenus = jdbc.query("""
                SELECT id, parent_id, code, display_name, icon, path, permission_code, sort_order, visible_to_operator, created_at, updated_at
                FROM platform_menus
                ORDER BY sort_order ASC
                """, (rs, n) -> new MenuRow(
                rs.getObject("id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("code"),
                rs.getString("display_name"),
                rs.getString("icon"),
                rs.getString("path"),
                rs.getString("permission_code"),
                rs.getInt("sort_order"),
                rs.getBoolean("visible_to_operator"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        ));
        return buildTree(allMenus);
    }

    /**
     * 获取当前管理员角色对应的菜单树。
     * 如果是 PLATFORM_OPERATOR 角色，只返回 visible_to_operator=true 的菜单。
     */
    public List<MenuNode> getMenuTreeForAdmin(String role) {
        List<MenuRow> allMenus;
        if ("PLATFORM_OPERATOR".equals(role)) {
            allMenus = jdbc.query("""
                    SELECT id, parent_id, code, display_name, icon, path, permission_code, sort_order, visible_to_operator, created_at, updated_at
                    FROM platform_menus
                    WHERE visible_to_operator = true
                    ORDER BY sort_order ASC
                    """, (rs, n) -> new MenuRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("parent_id", UUID.class),
                    rs.getString("code"),
                    rs.getString("display_name"),
                    rs.getString("icon"),
                    rs.getString("path"),
                    rs.getString("permission_code"),
                    rs.getInt("sort_order"),
                    rs.getBoolean("visible_to_operator"),
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                    rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
            ));
        } else {
            allMenus = jdbc.query("""
                    SELECT id, parent_id, code, display_name, icon, path, permission_code, sort_order, visible_to_operator, created_at, updated_at
                    FROM platform_menus
                    ORDER BY sort_order ASC
                    """, (rs, n) -> new MenuRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("parent_id", UUID.class),
                    rs.getString("code"),
                    rs.getString("display_name"),
                    rs.getString("icon"),
                    rs.getString("path"),
                    rs.getString("permission_code"),
                    rs.getInt("sort_order"),
                    rs.getBoolean("visible_to_operator"),
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                    rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
            ));
        }
        return buildTree(allMenus);
    }

    /**
     * 获取单个菜单详情。
     */
    public MenuRow getMenu(UUID menuId) {
        List<MenuRow> rows = jdbc.query("""
                SELECT id, parent_id, code, display_name, icon, path, permission_code, sort_order, visible_to_operator, created_at, updated_at
                FROM platform_menus
                WHERE id = ?
                """, (rs, n) -> new MenuRow(
                rs.getObject("id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("code"),
                rs.getString("display_name"),
                rs.getString("icon"),
                rs.getString("path"),
                rs.getString("permission_code"),
                rs.getInt("sort_order"),
                rs.getBoolean("visible_to_operator"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        ), menuId);
        if (rows.isEmpty()) {
            throw new ApiException("MENU_NOT_FOUND", "菜单不存在", HttpStatus.NOT_FOUND);
        }
        return rows.getFirst();
    }

    /**
     * 创建新菜单。
     */
    @Transactional
    public MenuRow createMenu(UUID parentId, String code, String displayName, String icon, String path,
                               String permissionCode, int sortOrder, boolean visibleToOperator) {
        // 如果有父菜单，验证父菜单存在
        if (parentId != null) {
            getMenu(parentId);
        }

        Instant now = Instant.now();
        UUID menuId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO platform_menus (id, parent_id, code, display_name, icon, path, permission_code, sort_order, visible_to_operator, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, menuId, parentId, required(code, "菜单编码不能为空"), required(displayName, "菜单名称不能为空"),
                icon, path, permissionCode, sortOrder, visibleToOperator, timestamp(now), timestamp(now));

        return new MenuRow(menuId, parentId, code, displayName, icon, path, permissionCode, sortOrder,
                visibleToOperator, now, now);
    }

    /**
     * 更新菜单信息。
     */
    @Transactional
    public MenuRow updateMenu(UUID menuId, UUID parentId, String code, String displayName, String icon,
                               String path, String permissionCode, Integer sortOrder, Boolean visibleToOperator) {
        MenuRow existing = getMenu(menuId);

        // 如果有父菜单，验证父菜单存在且不能是自己
        UUID newParentId = parentId != null ? parentId : existing.parentId();
        if (newParentId != null) {
            if (newParentId.equals(menuId)) {
                throw new ApiException("INVALID_PARENT", "父菜单不能是自己", HttpStatus.BAD_REQUEST);
            }
            getMenu(newParentId);
        }

        String newCode = code != null ? code : existing.code();
        String newDisplayName = displayName != null ? displayName : existing.displayName();
        String newIcon = icon != null ? icon : existing.icon();
        String newPath = path != null ? path : existing.path();
        String newPermissionCode = permissionCode != null ? permissionCode : existing.permissionCode();
        int newSortOrder = sortOrder != null ? sortOrder : existing.sortOrder();
        boolean newVisibleToOperator = visibleToOperator != null ? visibleToOperator : existing.visibleToOperator();
        Instant now = Instant.now();

        jdbc.update("""
                UPDATE platform_menus
                SET parent_id = ?, code = ?, display_name = ?, icon = ?, path = ?, permission_code = ?, sort_order = ?, visible_to_operator = ?, updated_at = ?
                WHERE id = ?
                """, newParentId, newCode, newDisplayName, newIcon, newPath, newPermissionCode, newSortOrder,
                newVisibleToOperator, timestamp(now), menuId);

        return new MenuRow(menuId, newParentId, newCode, newDisplayName, newIcon, newPath, newPermissionCode,
                newSortOrder, newVisibleToOperator, existing.createdAt(), now);
    }

    /**
     * 删除菜单，同时级联删除所有子菜单。
     */
    @Transactional
    public void deleteMenu(UUID menuId) {
        getMenu(menuId); // 确保菜单存在

        // 递归查找所有子菜单ID
        Set<UUID> allIds = new HashSet<>();
        collectChildIds(menuId, allIds);
        allIds.add(menuId);

        // 批量删除所有相关菜单
        for (UUID id : allIds) {
            jdbc.update("DELETE FROM platform_menus WHERE id = ?", id);
        }
    }

    /**
     * 更新菜单排序序号。
     */
    @Transactional
    public void updateSort(UUID menuId, int sortOrder) {
        getMenu(menuId); // 确保菜单存在
        jdbc.update("""
                UPDATE platform_menus SET sort_order = ?, updated_at = ?
                WHERE id = ?
                """, sortOrder, timestamp(Instant.now()), menuId);
    }

    /**
     * 递归收集所有子菜单ID。
     */
    private void collectChildIds(UUID parentId, Set<UUID> result) {
        List<UUID> childIds = jdbc.query("""
                SELECT id FROM platform_menus WHERE parent_id = ?
                """, (rs, n) -> rs.getObject("id", UUID.class), parentId);
        for (UUID childId : childIds) {
            if (result.add(childId)) {
                collectChildIds(childId, result);
            }
        }
    }

    /**
     * 将扁平菜单列表构建为树形结构。
     */
    private List<MenuNode> buildTree(List<MenuRow> rows) {
        Map<UUID, MenuNode> nodeMap = new LinkedHashMap<>();
        List<MenuNode> roots = new ArrayList<>();

        // 先创建所有节点
        for (MenuRow row : rows) {
            MenuNode node = new MenuNode(
                    row.id(), row.parentId(), row.code(), row.displayName(),
                    row.icon(), row.path(), row.permissionCode(), row.sortOrder(),
                    row.visibleToOperator(), row.createdAt(), row.updatedAt(),
                    new ArrayList<>()
            );
            nodeMap.put(row.id(), node);
        }

        // 构建父子关系
        for (MenuNode node : nodeMap.values()) {
            if (node.parentId() == null) {
                roots.add(node);
            } else {
                MenuNode parent = nodeMap.get(node.parentId());
                if (parent != null) {
                    parent.children().add(node);
                } else {
                    // 父菜单不在当前结果集中，当作根节点处理
                    roots.add(node);
                }
            }
        }

        return roots;
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    // ---- 数据记录 ----

    /**
     * 菜单数据行（扁平结构）。
     */
    public record MenuRow(
            UUID id,
            UUID parentId,
            String code,
            String displayName,
            String icon,
            String path,
            String permissionCode,
            int sortOrder,
            boolean visibleToOperator,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * 菜单树节点（包含子节点列表）。
     */
    public record MenuNode(
            UUID id,
            UUID parentId,
            String code,
            String displayName,
            String icon,
            String path,
            String permissionCode,
            int sortOrder,
            boolean visibleToOperator,
            Instant createdAt,
            Instant updatedAt,
            List<MenuNode> children
    ) {}
}