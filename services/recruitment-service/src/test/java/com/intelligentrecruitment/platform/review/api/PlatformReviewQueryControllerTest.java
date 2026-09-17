package com.intelligentrecruitment.platform.review.api;

import com.intelligentrecruitment.platform.review.application.ReviewQueryService;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard;
import com.intelligentrecruitment.shared.security.PlatformAdminGuard.PlatformAdminInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PlatformReviewQueryController 集成测试。
 * 使用 MockMvc 验证 HTTP 端点的请求参数、权限校验和响应格式。
 */
@WebMvcTest(controllers = PlatformReviewQueryController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.intelligentrecruitment\\.shared\\..*"))
@AutoConfigureMockMvc(addFilters = false)
class PlatformReviewQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReviewQueryService reviewQueryService;

    @MockBean
    private PlatformAdminGuard guard;

    private static final String ADMIN_KEY = "test-admin-key";
    private static final PlatformAdminInfo SUPER_ADMIN = new PlatformAdminInfo(
            UUID.randomUUID(), UUID.randomUUID(), "超级管理员", "SUPER_ADMIN");

    @Test
    void listPersonalReviews_withValidKey_returnsPagedResult() throws Exception {
        when(guard.authenticate(ADMIN_KEY)).thenReturn(SUPER_ADMIN);
        when(reviewQueryService.listPersonalReviews(eq("PENDING"), anyInt(), anyInt()))
                .thenReturn(new ReviewQueryService.PagedResult<>(List.of(
                        new ReviewQueryService.PersonalReviewRow(
                                UUID.randomUUID(), UUID.randomUUID(), "hash",
                                "张*", "PENDING", null, null, null,
                                Instant.now(), "张三", "8000")
                ), 1, 1, 20));

        mockMvc.perform(get("/api/v1/platform/reviews/personal")
                        .header("X-Platform-Admin-Key", ADMIN_KEY)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userDisplayName").value("张三"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listPersonalReviews_withoutKey_returnsClientError() throws Exception {
        mockMvc.perform(get("/api/v1/platform/reviews/personal"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void listCompanyVerifications_returnsData() throws Exception {
        when(guard.authenticate(ADMIN_KEY)).thenReturn(SUPER_ADMIN);
        when(reviewQueryService.listCompanyVerifications(eq("PENDING"), anyInt(), anyInt()))
                .thenReturn(new ReviewQueryService.PagedResult<>(List.of(
                        new ReviewQueryService.CompanyVerificationRow(
                                UUID.randomUUID(), UUID.randomUUID(), null,
                                "NEW", "测试企业", "测试企业", "hash", "9111****1234",
                                null, null, null, null,
                                "PENDING", null, null, null, Instant.now(), "申请人")
                ), 1, 1, 20));

        mockMvc.perform(get("/api/v1/platform/reviews/company-verifications")
                        .header("X-Platform-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].legalName").value("测试企业"));
    }

    @Test
    void listMembershipApplications_returnsData() throws Exception {
        when(guard.authenticate(ADMIN_KEY)).thenReturn(SUPER_ADMIN);
        when(reviewQueryService.listMembershipApplications(eq("PENDING"), anyInt(), anyInt()))
                .thenReturn(new ReviewQueryService.PagedResult<>(List.of(
                        new ReviewQueryService.MembershipApplicationRow(
                                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                "evidence", "PENDING", null, null, null,
                                Instant.now(), "申请人", "申请企业")
                ), 1, 1, 20));

        mockMvc.perform(get("/api/v1/platform/reviews/membership-applications")
                        .header("X-Platform-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].companyDisplayName").value("申请企业"));
    }

    @Test
    void getPersonalReviewDetail_returnsDetail() throws Exception {
        UUID userId = UUID.randomUUID();
        when(guard.authenticate(ADMIN_KEY)).thenReturn(SUPER_ADMIN);
        when(reviewQueryService.getPersonalReviewDetail(userId))
                .thenReturn(new ReviewQueryService.PersonalReviewRow(
                        UUID.randomUUID(), userId, "hash",
                        "李*", "PENDING", null, null, null,
                        Instant.now(), "李四", "9000"));

        mockMvc.perform(get("/api/v1/platform/reviews/personal/{userId}", userId)
                        .header("X-Platform-Admin-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userDisplayName").value("李四"));
    }
}
