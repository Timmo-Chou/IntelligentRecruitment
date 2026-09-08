package com.intelligentrecruitment.tenancy.api;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/** BFF routes for BOSS-owned Company lifecycle. Recruitment owns no Workspace lifecycle. */
@RestController
@RequestMapping("/api/v1/companies")
public class TenancyController {
    private final BossControlPlaneClient boss;
    public TenancyController(BossControlPlaneClient boss) { this.boss = boss; }

    @GetMapping
    List<CompanyContext> companies(Authentication authentication) {
        return boss.contexts(CurrentUser.bossAccessToken(authentication)).companies().stream()
                .map(item -> new CompanyContext(item.companyId(), item.tenantId(), item.legalName(), item.entityType(),
                        item.companyStatus(), item.tenantStatus(), item.companyOwner()))
                .toList();
    }

    /** Direct BOSS contexts; a PERSONAL tenant intentionally has no Company. */
    @GetMapping("/contexts")
    Contexts contexts(Authentication authentication) {
        var contexts = boss.contexts(CurrentUser.bossAccessToken(authentication));
        return new Contexts(contexts.userId(), contexts.tenants().stream()
                .map(item -> new TenantContext(item.tenantId(), item.tenantType(), item.tenantName(), item.tenantStatus())).toList(),
                contexts.companies().stream()
                        .map(item -> new CompanyContext(item.companyId(), item.tenantId(), item.legalName(), item.entityType(),
                                item.companyStatus(), item.tenantStatus(), item.companyOwner())).toList());
    }

    @PostMapping("/registrations")
    RegistrationResponse register(@Valid @RequestBody RegistrationRequest request, Authentication authentication) {
        var result = boss.registerCompany(CurrentUser.bossAccessToken(authentication), request.legalName(), request.creditCode(),
                request.licenseReference(), request.contactName(), request.contactPhone());
        return new RegistrationResponse(result.tenantId(), result.companyId(), result.verificationRequestId(), result.status());
    }

    /** BFF transport only: the file is persisted by BOSS and the returned reference is BOSS-owned. */
    @PostMapping(value = "/registrations/license-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    LicenseDocument uploadLicense(@RequestPart("file") MultipartFile file, Authentication authentication) {
        var result = boss.uploadLicenseDocument(CurrentUser.bossAccessToken(authentication), file);
        return new LicenseDocument(result.reference(), result.filename(), result.contentType(), result.sizeBytes());
    }

    @GetMapping("/search")
    List<CompanySearchResult> search(@RequestParam("q") String query, Authentication authentication) {
        return boss.searchCompanies(CurrentUser.bossAccessToken(authentication), query).stream()
                .map(item -> new CompanySearchResult(item.companyId(), item.legalName(), item.memberCount())).toList();
    }

    @PostMapping("/{companyId}/membership-applications")
    void apply(@PathVariable UUID companyId, Authentication authentication) {
        boss.applyForMembership(CurrentUser.bossAccessToken(authentication), companyId);
    }

    @GetMapping("/registrations/pending")
    List<PendingRegistration> pending(Authentication authentication) {
        return boss.pendingRegistrations(CurrentUser.bossAccessToken(authentication)).stream()
                .map(item -> new PendingRegistration(item.verificationRequestId(), item.companyId(), item.legalName(),
                        item.status(), item.createdAt())).toList();
    }

    @GetMapping("/{companyId}/governance/{resource:members|membership-applications|invitations}")
    JsonNode governance(@PathVariable UUID companyId, @PathVariable String resource, Authentication authentication) {
        return boss.companyGovernance(CurrentUser.bossAccessToken(authentication), companyId, resource);
    }

    @GetMapping("/{companyId}/org-units/accessible")
    JsonNode accessibleOrgUnits(@PathVariable UUID companyId, Authentication authentication) {
        return boss.accessibleOrgUnits(CurrentUser.bossAccessToken(authentication), companyId);
    }

    @GetMapping("/{companyId}/permissions")
    List<String> companyPermissions(@PathVariable UUID companyId, Authentication authentication) {
        return boss.companyPermissions(CurrentUser.bossAccessToken(authentication), companyId);
    }

    @PostMapping("/{companyId}/org-units/recent")
    void rememberOrgUnit(@PathVariable UUID companyId, @Valid @RequestBody RecentOrgUnit request, Authentication authentication) {
        boss.rememberOrgUnit(CurrentUser.bossAccessToken(authentication), companyId, request.orgUnitId());
    }

    @PostMapping("/{companyId}/invitations")
    JsonNode invite(@PathVariable UUID companyId, @Valid @RequestBody InvitationRequest request, Authentication authentication) {
        return boss.inviteCompanyMember(CurrentUser.bossAccessToken(authentication), companyId, request.phone());
    }

    @PostMapping("/membership-applications/{applicationId}/decision")
    void decideMembershipApplication(@PathVariable UUID applicationId, @Valid @RequestBody MembershipDecision request,
                                     Authentication authentication) {
        boss.decideMembershipApplication(CurrentUser.bossAccessToken(authentication), applicationId, request.approve(), request.reason());
    }

    @PostMapping("/{companyId}/members/{userId}/status")
    void setMemberStatus(@PathVariable UUID companyId, @PathVariable UUID userId, @Valid @RequestBody MemberStatus request,
                         Authentication authentication) {
        boss.setCompanyMemberStatus(CurrentUser.bossAccessToken(authentication), companyId, userId, request.status());
    }

    public record RegistrationRequest(@NotBlank String legalName, @NotBlank String creditCode,
                                      @NotBlank String licenseReference, @NotBlank String contactName,
                                      @NotBlank String contactPhone) { }
    public record RegistrationResponse(UUID tenantId, UUID companyId, UUID verificationRequestId, String status) { }
    public record LicenseDocument(String reference, String filename, String contentType, long sizeBytes) { }
    public record CompanyContext(UUID companyId, UUID tenantId, String legalName, String entityType,
                                 String companyStatus, String tenantStatus, boolean companyOwner) { }
    public record TenantContext(UUID tenantId, String tenantType, String tenantName, String tenantStatus) { }
    public record Contexts(UUID userId, List<TenantContext> tenants, List<CompanyContext> companies) { }
    public record CompanySearchResult(UUID companyId, String legalName, int memberCount) { }
    public record PendingRegistration(UUID verificationRequestId, UUID companyId, String legalName,
                                      String status, java.time.Instant createdAt) { }
    public record RecentOrgUnit(@jakarta.validation.constraints.NotNull UUID orgUnitId) { }
    public record InvitationRequest(@jakarta.validation.constraints.NotBlank
                                    @jakarta.validation.constraints.Pattern(regexp = "1\\d{10}") String phone) { }
    public record MembershipDecision(boolean approve, String reason) { }
    public record MemberStatus(@jakarta.validation.constraints.Pattern(regexp = "ACTIVE|DISABLED|REMOVED") String status) { }
}
