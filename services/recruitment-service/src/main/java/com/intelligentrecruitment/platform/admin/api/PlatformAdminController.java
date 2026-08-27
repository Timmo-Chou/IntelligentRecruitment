package com.intelligentrecruitment.platform.admin.api;

import com.intelligentrecruitment.platform.admin.application.PlatformAdminService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 平台管理员管理接口。
 * 提供管理员的CRUD操作，需要 admin:manage 权限。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformAdminController {

    private final PlatformAdminService adminService;
    private final PlatformAdminGuard guard;

    public PlatformAdminController(PlatformAdminService adminService, PlatformAdminGuard guard) {
        this.adminService = adminService;
        this.guard = guard;
    }

    /**
     * 获取当前管理员身份信息（用于登录验证）。
     */
    @GetMapping("/me")
    PlatformAdminInfo getCurrentAdmin(@RequestHeader("X-Platform-Admin-Key") String key) {
        return guard.authenticate(key);
    }

    /**
     * 获取所有平台管理员列表。
     */
    @GetMapping("/admins")
    List<PlatformAdminService.PlatformAdminRow> listAdmins(@RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "admin:manage");
        return adminService.listAdmins();
    }

    /**
     * 获取单个管理员详情。
     */
    @GetMapping("/admins/{adminId}")
    PlatformAdminService.PlatformAdminRow getAdmin(@PathVariable UUID adminId,
                                                    @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "admin:manage");
        return adminService.getAdmin(adminId);
    }

    /**
     * 创建新的平台管理员。
     */
    @PostMapping("/admins")
    PlatformAdminService.PlatformAdminRow createAdmin(@RequestHeader("X-Platform-Admin-Key") String key,
                                                       @Valid @RequestBody CreateAdminRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "admin:manage");
        return adminService.createAdmin(request.displayName(), request.key(), request.role());
    }

    /**
     * 更新管理员信息（角色和状态）。
     */
    @PutMapping("/admins/{adminId}")
    PlatformAdminService.PlatformAdminRow updateAdmin(@PathVariable UUID adminId,
                                                       @RequestHeader("X-Platform-Admin-Key") String key,
                                                       @Valid @RequestBody UpdateAdminRequest request) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "admin:manage");
        return adminService.updateAdmin(adminId, request.role(), request.status());
    }

    /**
     * 禁用管理员。
     */
    @PostMapping("/admins/{adminId}/disable")
    void disableAdmin(@PathVariable UUID adminId,
                      @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "admin:manage");
        adminService.disableAdmin(adminId);
    }

    /**
     * 删除管理员（物理删除）。
     */
    @DeleteMapping("/admins/{adminId}")
    void deleteAdmin(@PathVariable UUID adminId,
                      @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "admin:manage");
        adminService.deleteAdmin(adminId);
    }

    // ---- 请求体记录 ----

    public record CreateAdminRequest(
            @NotBlank String displayName,
            @NotBlank String key,
            @NotBlank String role
    ) {}

    public record UpdateAdminRequest(
            String role,
            String status
    ) {}
}