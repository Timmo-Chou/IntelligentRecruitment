package com.intelligentrecruitment.platform.user.api;

import com.intelligentrecruitment.platform.user.application.PlatformUserService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 平台用户管理 Controller。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformUserController {

    private final PlatformUserService service;
    private final PlatformAdminGuard guard;

    public PlatformUserController(PlatformUserService service, PlatformAdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/users")
    PlatformUserService.PagedResult<PlatformUserService.UserSummary> listUsers(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "user:read");
        return service.listUsers(search, status, page, pageSize);
    }

    @GetMapping("/users/{userId}")
    PlatformUserService.UserDetail getUserDetail(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @PathVariable UUID userId) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "user:read");
        return service.getUserDetail(userId);
    }

    @PostMapping("/users/{userId}/disable")
    void disableUser(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @PathVariable UUID userId) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "user:write");
        service.disableUser(userId);
    }

    @PostMapping("/users/{userId}/enable")
    void enableUser(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @PathVariable UUID userId) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "user:write");
        service.enableUser(userId);
    }
}