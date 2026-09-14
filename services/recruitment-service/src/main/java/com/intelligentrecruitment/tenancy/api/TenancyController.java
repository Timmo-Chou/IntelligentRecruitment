package com.intelligentrecruitment.tenancy.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Recruitment BFF for the BOSS-owned Tenant model. Recruitment has no Company lifecycle. */
@RestController
@RequestMapping("/api/v1/tenants")
public class TenancyController {
    private final BossControlPlaneClient boss;
    public TenancyController(BossControlPlaneClient boss) { this.boss = boss; }

    @GetMapping("/contexts")
    Contexts contexts(Authentication authentication) {
        var contexts = boss.contexts(token(authentication));
        return new Contexts(contexts.userId(), contexts.tenants().stream()
                .map(item -> new TenantContext(item.tenantId(), item.productDomain(), item.tenantType(), item.tenantName(),
                        item.tenantStatus(), item.roleCode(), item.owner(), item.seatAssigned())).toList());
    }

    @GetMapping("/enterprises/search")
    List<EnterpriseSearchResult> search(@RequestParam("keyword") @NotBlank @Size(max = 200) String keyword, Authentication authentication) {
        return boss.searchEnterprises(token(authentication), keyword).stream()
                .map(item -> new EnterpriseSearchResult(item.tenantId(), item.tenantName(), item.legalName())).toList();
    }

    @GetMapping("/enterprise-registration/credit-code-availability")
    Map<String, Object> creditCodeAvailability(@RequestParam @Pattern(regexp = "[0-9A-Z]{18}") String creditCode, Authentication authentication) {
        return boss.creditCodeAvailability(token(authentication), creditCode);
    }

    @PostMapping(value = "/enterprise-registration-documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    JsonNode uploadLicense(@RequestPart("file") MultipartFile file, Authentication authentication) {
        return boss.uploadEnterpriseLicense(token(authentication), file);
    }

    @PostMapping("/enterprise-registrations")
    JsonNode register(@Valid @RequestBody RegistrationRequest request, Authentication authentication) {
        return boss.submitEnterpriseRegistration(token(authentication), request.legalName(), request.creditCode(), request.licenseDocumentId(), request.contactName(), request.contactPhone());
    }

    @PostMapping("/{tenantId}/join-applications")
    JsonNode apply(@PathVariable UUID tenantId, @Valid @RequestBody JoinApplicationRequest request, Authentication authentication) {
        return boss.applyToJoin(token(authentication), tenantId, request.roleCode(), request.reason());
    }

    @GetMapping("/{tenantId}/overview") JsonNode overview(@PathVariable UUID tenantId, Authentication a) { return boss.enterpriseOverview(token(a), tenantId); }
    @GetMapping("/{tenantId}/members") JsonNode members(@PathVariable UUID tenantId, Authentication a) { return boss.enterpriseMembers(token(a), tenantId); }
    @GetMapping("/{tenantId}/invitations") JsonNode invitations(@PathVariable UUID tenantId, Authentication a) { return boss.enterpriseInvitations(token(a), tenantId); }
    @GetMapping("/{tenantId}/join-applications") JsonNode applications(@PathVariable UUID tenantId, Authentication a) { return boss.enterpriseJoinApplications(token(a), tenantId); }
    @GetMapping("/{tenantId}/billing") JsonNode billing(@PathVariable UUID tenantId, Authentication a) { return boss.enterpriseBilling(token(a), tenantId); }
    @GetMapping("/{tenantId}/roles") JsonNode roles(@PathVariable UUID tenantId, Authentication a) { return boss.enterpriseRoles(token(a), tenantId); }
    @GetMapping("/{tenantId}/permission-catalog") JsonNode permissionCatalog(@PathVariable UUID tenantId, Authentication a) { return boss.permissionCatalog(token(a), tenantId); }

    @PostMapping("/{tenantId}/invitations")
    JsonNode invite(@PathVariable UUID tenantId, @Valid @RequestBody InvitationRequest r, Authentication a) {
        return boss.createInvitation(token(a), tenantId, r.roleCode(), r.maxUses(), r.expiresAt(), r.note());
    }
    @PostMapping("/invitations/claim") void claim(@Valid @RequestBody ClaimInvitationRequest r, Authentication a) { boss.claimInvitation(token(a), r.invitationToken()); }
    @PostMapping("/join-applications/{applicationId}/decision") void decide(@PathVariable UUID applicationId, @Valid @RequestBody DecisionRequest r, Authentication a) { boss.decideJoinApplication(token(a), applicationId, r.approve(), r.reason()); }
    @PostMapping("/{tenantId}/owner-seat/assign") void assignOwnerSeat(@PathVariable UUID tenantId, Authentication a) { boss.assignOwnerSeat(token(a), tenantId); }
    @PostMapping("/{tenantId}/owner-seat/release") void releaseOwnerSeat(@PathVariable UUID tenantId, Authentication a) { boss.releaseOwnerSeat(token(a), tenantId); }
    @PostMapping("/{tenantId}/members/{userId}/remove") void removeMember(@PathVariable UUID tenantId, @PathVariable UUID userId, Authentication a) { boss.removeMember(token(a), tenantId, userId); }
    @PutMapping("/{tenantId}/feature-settings") void updateFeatureSettings(@PathVariable UUID tenantId, @Valid @RequestBody FeatureSettingsRequest r, Authentication a) { boss.updateFeatureSettings(token(a), tenantId, r.talentPoolSharingEnabled(), r.jobPoolSharingEnabled()); }
    @PostMapping("/{tenantId}/roles") JsonNode createRole(@PathVariable UUID tenantId, @Valid @RequestBody RoleRequest r, Authentication a) { return boss.createRole(token(a), tenantId, r.code(), r.displayName(), r.permissionCodes()); }
    @PutMapping("/{tenantId}/roles/{roleId}/permissions") void updateRolePermissions(@PathVariable UUID tenantId, @PathVariable UUID roleId, @Valid @RequestBody RolePermissionsRequest r, Authentication a) { boss.updateRolePermissions(token(a), tenantId, roleId, r.permissionCodes()); }
    @PutMapping("/{tenantId}/members/{userId}/role") void updateMemberRole(@PathVariable UUID tenantId, @PathVariable UUID userId, @Valid @RequestBody MemberRoleRequest r, Authentication a) { boss.updateMemberRole(token(a), tenantId, userId, r.roleCode()); }

    private static String token(Authentication authentication) { return CurrentUser.bossAccessToken(authentication); }
    public record Contexts(UUID userId, List<TenantContext> tenants) { }
    public record TenantContext(UUID tenantId, String productDomain, String tenantType, String tenantName, String tenantStatus, String roleCode, boolean owner, boolean seatAssigned) { }
    public record EnterpriseSearchResult(UUID tenantId, String tenantName, String legalName) { }
    public record RegistrationRequest(@NotBlank @Size(max = 200) String legalName, @NotBlank @Pattern(regexp = "[0-9A-Z]{18}") String creditCode, @NotNull UUID licenseDocumentId, @NotBlank @Size(max = 80) String contactName, @NotBlank @Pattern(regexp = "1\\d{10}") String contactPhone) { }
    public record JoinApplicationRequest(@NotBlank @Size(max = 64) String roleCode, @Size(max = 500) String reason) { }
    public record InvitationRequest(@NotBlank @Size(max = 64) String roleCode, @Min(1) @Max(10000) int maxUses, @NotNull Instant expiresAt, @Size(max = 500) String note) { }
    public record ClaimInvitationRequest(@NotBlank String invitationToken) { }
    public record DecisionRequest(boolean approve, @Size(max = 500) String reason) { }
    public record FeatureSettingsRequest(boolean talentPoolSharingEnabled, boolean jobPoolSharingEnabled) { }
    public record RoleRequest(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String code, @NotBlank @Size(max = 80) String displayName, @NotNull JsonNode permissionCodes) { }
    public record RolePermissionsRequest(@NotNull JsonNode permissionCodes) { }
    public record MemberRoleRequest(@NotBlank @Size(max = 64) String roleCode) { }
}
