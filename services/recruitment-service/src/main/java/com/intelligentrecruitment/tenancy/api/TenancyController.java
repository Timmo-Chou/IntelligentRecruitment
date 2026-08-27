package com.intelligentrecruitment.tenancy.api;

import com.intelligentrecruitment.shared.security.CurrentUser;
import com.intelligentrecruitment.tenancy.application.TenancyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TenancyController {
    private final TenancyService tenancy;

    public TenancyController(TenancyService tenancy) {
        this.tenancy = tenancy;
    }

    @PostMapping("/workspaces/personal")
    TenancyService.WorkspaceView createPersonal(@Valid @RequestBody WorkspaceName request, Authentication authentication) {
        return tenancy.createPersonalWorkspace(CurrentUser.id(authentication), request.name());
    }

    @PostMapping("/personal-verifications")
    IdResponse submitPersonalVerification(@Valid @RequestBody PersonalVerificationRequest request,
                                          Authentication authentication) {
        return new IdResponse(tenancy.submitPersonalVerification(CurrentUser.id(authentication),
                request.realName(), request.identityNumber()));
    }

    @PostMapping("/company-verifications")
    IdResponse submitCompanyVerification(@Valid @RequestBody CompanyVerificationRequest request,
                                         Authentication authentication) {
        return new IdResponse(tenancy.submitCompanyVerification(CurrentUser.id(authentication),
                new TenancyService.CompanyVerificationInput(request.legalName(), request.displayName(),
                        request.creditCode(), request.licenseReference(), request.firstWorkspaceName())));
    }

    @GetMapping("/companies")
    List<TenancyService.CompanyView> companies(Authentication authentication) {
        return tenancy.listCompanies(CurrentUser.id(authentication));
    }

    @GetMapping("/workspaces")
    List<TenancyService.WorkspaceView> workspaces(Authentication authentication) {
        return tenancy.listWorkspaces(CurrentUser.id(authentication));
    }

    @PostMapping("/companies/{companyId}/workspaces")
    TenancyService.WorkspaceView createCompanyWorkspace(@PathVariable UUID companyId,
                                                        @Valid @RequestBody CreateCompanyWorkspaceRequest request,
                                                        Authentication authentication) {
        return tenancy.createCompanyWorkspace(CurrentUser.id(authentication), companyId, request.name(), request.ownerUserId());
    }

    @PostMapping("/companies/{companyId}/membership-applications")
    IdResponse apply(@PathVariable UUID companyId, @Valid @RequestBody MembershipApplicationRequest request,
                     Authentication authentication) {
        return new IdResponse(tenancy.applyToCompany(CurrentUser.id(authentication), companyId, request.evidence()));
    }

    @GetMapping("/companies/search")
    List<TenancyService.CompanySearchResult> searchCompanies(@RequestParam("q") String query) {
        return tenancy.searchCompanies(query);
    }

    @GetMapping("/companies/{companyId}/membership-applications")
    List<TenancyService.MembershipApplicationView> applications(@PathVariable UUID companyId, Authentication authentication) {
        return tenancy.listCompanyApplications(CurrentUser.id(authentication), companyId);
    }

    @PostMapping("/companies/{companyId}/membership-applications/{applicationId}/approve")
    void approveApplication(@PathVariable UUID companyId, @PathVariable UUID applicationId, Authentication authentication) {
        tenancy.approveCompanyApplication(CurrentUser.id(authentication), applicationId);
    }

    @PostMapping("/companies/{companyId}/membership-applications/{applicationId}/reject")
    void rejectApplication(@PathVariable UUID companyId, @PathVariable UUID applicationId,
                           @Valid @RequestBody RejectionRequest request, Authentication authentication) {
        tenancy.rejectCompanyApplication(CurrentUser.id(authentication), applicationId, request.reason());
    }

    @PostMapping("/companies/{companyId}/invitations")
    TenancyService.Invitation companyInvitation(@PathVariable UUID companyId, @Valid @RequestBody InvitationRequest request,
                                                Authentication authentication) {
        return tenancy.createCompanyInvitation(CurrentUser.id(authentication), companyId, request.phone(), request.role());
    }

    @PostMapping("/workspaces/{workspaceId}/invitations")
    TenancyService.Invitation workspaceInvitation(@PathVariable UUID workspaceId, @Valid @RequestBody InvitationRequest request,
                                                  Authentication authentication) {
        return tenancy.createWorkspaceInvitation(CurrentUser.id(authentication), workspaceId, request.phone(), request.role());
    }

    @PostMapping("/membership-invitations/accept")
    void acceptInvitation(@Valid @RequestBody AcceptInvitationRequest request, Authentication authentication) {
        tenancy.acceptInvitation(CurrentUser.id(authentication), request.token());
    }

    public record WorkspaceName(@NotBlank String name) {}
    public record PersonalVerificationRequest(@NotBlank String realName, @NotBlank String identityNumber) {}
    public record CompanyVerificationRequest(@NotBlank String legalName, @NotBlank String displayName,
                                             @NotBlank String creditCode, @NotBlank String licenseReference,
                                             @NotBlank String firstWorkspaceName) {}
    public record CreateCompanyWorkspaceRequest(@NotBlank String name, @NotNull UUID ownerUserId) {}
    public record MembershipApplicationRequest(@NotBlank String evidence) {}
    public record RejectionRequest(@NotBlank String reason) {}
    public record InvitationRequest(@NotBlank String phone, @NotBlank String role) {}
    public record AcceptInvitationRequest(@NotBlank String token) {}
    public record IdResponse(UUID id) {}
}
