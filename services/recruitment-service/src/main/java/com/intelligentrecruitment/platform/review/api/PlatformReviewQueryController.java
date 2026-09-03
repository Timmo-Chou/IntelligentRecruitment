package com.intelligentrecruitment.platform.review.api;

import com.intelligentrecruitment.platform.review.application.ReviewQueryService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 平台审核查询接口。
 * 提供待审核项的列表查询和详情查看。
 */
@RestController
@RequestMapping("/api/v1/platform")
public class PlatformReviewQueryController {

    private final ReviewQueryService reviewQueryService;
    private final PlatformAdminGuard guard;

    public PlatformReviewQueryController(ReviewQueryService reviewQueryService, PlatformAdminGuard guard) {
        this.reviewQueryService = reviewQueryService;
        this.guard = guard;
    }

    /**
     * 查询个人认证列表。
     * @param status PENDING=待审核；HISTORY=已审核；空=全部
     */
    @GetMapping("/reviews/personal")
    ReviewQueryService.PagedResult<ReviewQueryService.PersonalReviewRow> listPersonalReviews(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "verification:review");
        return reviewQueryService.listPersonalReviews(status, page, size);
    }

    /**
     * 查询企业认证列表。
     * @param status PENDING=待审核；HISTORY=已审核；空=全部
     */
    @GetMapping("/reviews/company-verifications")
    ReviewQueryService.PagedResult<ReviewQueryService.CompanyVerificationRow> listCompanyVerifications(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "verification:review");
        return reviewQueryService.listCompanyVerifications(status, page, size);
    }

    /**
     * 查询企业加入申请列表。
     * @param status PENDING=待审核；HISTORY=已审核；空=全部
     */
    @GetMapping("/reviews/membership-applications")
    ReviewQueryService.PagedResult<ReviewQueryService.MembershipApplicationRow> listMembershipApplications(
            @RequestHeader("X-Platform-Admin-Key") String key,
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "membership:review");
        return reviewQueryService.listMembershipApplications(status, page, size);
    }

    /**
     * 获取个人认证详情。
     */
    @GetMapping("/reviews/personal/{userId}")
    ReviewQueryService.PersonalReviewRow getPersonalReviewDetail(
            @PathVariable UUID userId,
            @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "verification:review");
        return reviewQueryService.getPersonalReviewDetail(userId);
    }

    /**
     * 获取企业认证详情。
     */
    @GetMapping("/reviews/company-verifications/{requestId}")
    ReviewQueryService.CompanyVerificationRow getCompanyVerificationDetail(
            @PathVariable UUID requestId,
            @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "verification:review");
        return reviewQueryService.getCompanyVerificationDetail(requestId);
    }

    /**
     * 获取企业加入申请详情。
     */
    @GetMapping("/reviews/membership-applications/{applicationId}")
    ReviewQueryService.MembershipApplicationRow getMembershipApplicationDetail(
            @PathVariable UUID applicationId,
            @RequestHeader("X-Platform-Admin-Key") String key) {
        PlatformAdminInfo admin = guard.authenticate(key);
        guard.requirePermission(admin, "membership:review");
        return reviewQueryService.getMembershipApplicationDetail(applicationId);
    }
}