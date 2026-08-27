package com.intelligentrecruitment.platform.menu.api;

import com.intelligentrecruitment.platform.menu.application.MenuService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 平台菜单管理接口。
 * 提供菜单树的CRUD操作，需要 menu:manage 权限。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformMenuController {

    private final MenuService menuService;
    private final PlatformAdminGuard guard;

    public PlatformMenuController(MenuService menuService, PlatformAdminGuard guard) {
        this.menuService = menuService;
        this.guard = guard;
    }

    /**
     * 获取完整菜单树。
     */
    @GetMapping("/menus")
    List<MenuService.MenuNode> getMenuTree(@RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "menu:manage");
        return menuService.getMenuTree();
    }

    /**
     * 获取当前管理员的菜单树（根据角色过滤可见性，无需权限校验）。
     */
    @GetMapping("/me/menus")
    List<MenuService.MenuNode> getMyMenuTree(@RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        return menuService.getMenuTreeForAdmin(admin.role());
    }

    /**
     * 获取单个菜单详情。
     */
    @GetMapping("/menus/{menuId}")
    MenuService.MenuRow getMenu(@PathVariable UUID menuId,
                                 @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "menu:manage");
        return menuService.getMenu(menuId);
    }

    /**
     * 创建新菜单。
     */
    @PostMapping("/menus")
    MenuService.MenuRow createMenu(@RequestHeader("X-Platform-Admin-Key") String key,
                                    @Valid @RequestBody CreateMenuRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "menu:manage");
        return menuService.createMenu(
                request.parentId(), request.code(), request.displayName(), request.icon(),
                request.path(), request.permissionCode(), request.sortOrder(), request.visibleToOperator()
        );
    }

    /**
     * 更新菜单。
     */
    @PutMapping("/menus/{menuId}")
    MenuService.MenuRow updateMenu(@PathVariable UUID menuId,
                                    @RequestHeader("X-Platform-Admin-Key") String key,
                                    @Valid @RequestBody UpdateMenuRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "menu:manage");
        return menuService.updateMenu(
                menuId, request.parentId(), request.code(), request.displayName(), request.icon(),
                request.path(), request.permissionCode(), request.sortOrder(), request.visibleToOperator()
        );
    }

    /**
     * 删除菜单（级联删除子菜单）。
     */
    @DeleteMapping("/menus/{menuId}")
    void deleteMenu(@PathVariable UUID menuId,
                    @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "menu:manage");
        menuService.deleteMenu(menuId);
    }

    /**
     * 更新菜单排序。
     */
    @PutMapping("/menus/{menuId}/sort")
    void updateSort(@PathVariable UUID menuId,
                    @RequestHeader("X-Platform-Admin-Key") String key,
                    @Valid @RequestBody UpdateSortRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "menu:manage");
        menuService.updateSort(menuId, request.sortOrder());
    }

    // ---- 请求体记录 ----

    public record CreateMenuRequest(
            UUID parentId,
            @NotBlank String code,
            @NotBlank String displayName,
            String icon,
            String path,
            String permissionCode,
            int sortOrder,
            boolean visibleToOperator
    ) {}

    public record UpdateMenuRequest(
            UUID parentId,
            String code,
            String displayName,
            String icon,
            String path,
            String permissionCode,
            Integer sortOrder,
            Boolean visibleToOperator
    ) {}

    public record UpdateSortRequest(
            int sortOrder
    ) {}
}