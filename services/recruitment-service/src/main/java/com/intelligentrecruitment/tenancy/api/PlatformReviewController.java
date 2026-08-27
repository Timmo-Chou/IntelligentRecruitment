package com.intelligentrecruitment.tenancy.api;

import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.tenancy.application.TenancyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformReviewController {
    private final TenancyService tenancy;
    private final PlatformAdminGuard guard;

    public PlatformReviewController(TenancyService tenancy, PlatformAdminGuard guard) {
        this.tenancy = tenancy;
        this.guard = guard;
    }

    @PostMapping("/personal-verifications/{userId}/approve")
    void approvePersonal(@PathVariable UUID userId, @RequestHeader("X-Platform-Admin-Key") String key,
                         @Valid @RequestBody ReviewRequest request) {
        guard.require(key);
        tenancy.approvePersonalVerification(userId, request.reviewer());
    }

    @PostMapping("/personal-verifications/{userId}/reject")
    void rejectPersonal(@PathVariable UUID userId,@RequestHeader("X-Platform-Admin-Key") String key,@Valid @RequestBody RejectionRequest request){guard.require(key);tenancy.rejectPersonalVerification(userId,request.reviewer(),request.reason());}

    @PostMapping("/company-verifications/{requestId}/approve")
    TenancyService.CompanyApproval approveCompany(@PathVariable UUID requestId,
                                                  @RequestHeader("X-Platform-Admin-Key") String key,
                                                  @Valid @RequestBody ReviewRequest request) {
        guard.require(key);
        return tenancy.approveCompanyVerification(requestId, request.reviewer());
    }

    @PostMapping("/company-verifications/{requestId}/reject")
    void rejectCompany(@PathVariable UUID requestId,@RequestHeader("X-Platform-Admin-Key") String key,@Valid @RequestBody RejectionRequest request){guard.require(key);tenancy.rejectCompanyVerification(requestId,request.reviewer(),request.reason());}

    public record ReviewRequest(@NotBlank String reviewer) {}
    public record RejectionRequest(@NotBlank String reviewer,@NotBlank String reason) {}
}
