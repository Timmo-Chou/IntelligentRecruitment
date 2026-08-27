package com.intelligentrecruitment.platform.company.api;

import com.intelligentrecruitment.platform.company.application.PlatformCompanyService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 平台企业管理 Controller。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformCompanyController {

    private final PlatformCompanyService service;
    private final PlatformAdminGuard guard;

    public PlatformCompanyController(PlatformCompanyService service, PlatformAdminGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/companies")
    PlatformCompanyService.PagedResult<PlatformCompanyService.CompanySummary> listCompanies(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "company:read");
        return service.listCompanies(search, status, page, pageSize);
    }

    @GetMapping("/companies/{companyId}")
    PlatformCompanyService.CompanyDetail getCompanyDetail(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @PathVariable UUID companyId) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "company:read");
        return service.getCompanyDetail(companyId);
    }
}