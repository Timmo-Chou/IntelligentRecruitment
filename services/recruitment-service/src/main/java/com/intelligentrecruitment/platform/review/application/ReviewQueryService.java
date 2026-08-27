package com.intelligentrecruitment.platform.review.application;

import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 审核查询服务。
 * 负责查询待审核的个人认证、企业认证、企业加入申请等。
 */
@Service
public class ReviewQueryService {

    private final JdbcTemplate jdbc;

    public ReviewQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询待审核的个人身份认证列表（分页）。
     */
    public PagedResult<PersonalReviewRow> listPersonalReviews(int page, int size) {
        int offset = (page - 1) * size;

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM personal_identities WHERE verification_status = 'PENDING'
                """, Long.class);

        List<PersonalReviewRow> rows = jdbc.query("""
                SELECT pi.id, pi.user_id, pi.identity_hash, pi.real_name_masked, pi.verification_status,
                       pi.reviewed_by, pi.reviewed_at, pi.rejection_reason, pi.created_at,
                       u.display_name AS user_display_name,
                       u.phone_last_four AS phone_last_four
                FROM personal_identities pi
                LEFT JOIN users u ON u.id = pi.user_id
                WHERE pi.verification_status = 'PENDING'
                ORDER BY pi.created_at ASC
                LIMIT ? OFFSET ?
                """, (rs, n) -> new PersonalReviewRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("identity_hash"),
                rs.getString("real_name_masked"),
                rs.getString("verification_status"),
                rs.getString("reviewed_by"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getString("rejection_reason"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getString("user_display_name"),
                rs.getString("phone_last_four")
        ), size, offset);

        return new PagedResult<>(rows, total != null ? total : 0, page, size);
    }

    /**
     * 查询待审核的企业认证请求列表（分页）。
     */
    public PagedResult<CompanyVerificationRow> listCompanyVerifications(int page, int size) {
        int offset = (page - 1) * size;

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM company_verification_requests WHERE status = 'PENDING'
                """, Long.class);

        List<CompanyVerificationRow> rows = jdbc.query("""
                SELECT cvr.id, cvr.applicant_user_id, cvr.company_id, cvr.request_type, cvr.legal_name, cvr.display_name,
                       cvr.credit_code_hash, cvr.credit_code_masked, cvr.license_reference, cvr.first_workspace_name,
                       cvr.status, cvr.reviewed_by, cvr.reviewed_at, cvr.rejection_reason, cvr.created_at,
                       u.display_name AS applicant_display_name
                FROM company_verification_requests cvr
                LEFT JOIN users u ON u.id = cvr.applicant_user_id
                WHERE cvr.status = 'PENDING'
                ORDER BY cvr.created_at ASC
                LIMIT ? OFFSET ?
                """, (rs, n) -> new CompanyVerificationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("applicant_user_id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("request_type"),
                rs.getString("legal_name"),
                rs.getString("display_name"),
                rs.getString("credit_code_hash"),
                rs.getString("credit_code_masked"),
                rs.getString("license_reference"),
                rs.getString("first_workspace_name"),
                rs.getString("status"),
                rs.getString("reviewed_by"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getString("rejection_reason"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getString("applicant_display_name")
        ), size, offset);

        return new PagedResult<>(rows, total != null ? total : 0, page, size);
    }

    /**
     * 查询待审核的企业加入申请列表（分页）。
     */
    public PagedResult<MembershipApplicationRow> listMembershipApplications(int page, int size) {
        int offset = (page - 1) * size;

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM membership_applications WHERE status = 'PENDING'
                """, Long.class);

        List<MembershipApplicationRow> rows = jdbc.query("""
                SELECT ma.id, ma.company_id, ma.applicant_user_id, ma.evidence, ma.status,
                       ma.reviewed_by_platform_user, ma.reviewed_at, ma.review_reason, ma.created_at,
                       u.display_name AS user_display_name,
                       c.display_name AS company_display_name
                FROM membership_applications ma
                LEFT JOIN users u ON u.id = ma.applicant_user_id
                LEFT JOIN companies c ON c.id = ma.company_id
                WHERE ma.status = 'PENDING'
                ORDER BY ma.created_at ASC
                LIMIT ? OFFSET ?
                """, (rs, n) -> new MembershipApplicationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getObject("applicant_user_id", UUID.class),
                rs.getString("evidence"),
                rs.getString("status"),
                rs.getString("reviewed_by_platform_user"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getString("review_reason"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getString("user_display_name"),
                rs.getString("company_display_name")
        ), size, offset);

        return new PagedResult<>(rows, total != null ? total : 0, page, size);
    }

    /**
     * 获取个人认证详情。
     */
    public PersonalReviewRow getPersonalReviewDetail(UUID userId) {
        List<PersonalReviewRow> rows = jdbc.query("""
                SELECT pi.id, pi.user_id, pi.identity_hash, pi.real_name_masked, pi.verification_status,
                       pi.reviewed_by, pi.reviewed_at, pi.rejection_reason, pi.created_at,
                       u.display_name AS user_display_name,
                       u.phone_last_four AS phone_last_four
                FROM personal_identities pi
                LEFT JOIN users u ON u.id = pi.user_id
                WHERE pi.user_id = ?
                """, (rs, n) -> new PersonalReviewRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("identity_hash"),
                rs.getString("real_name_masked"),
                rs.getString("verification_status"),
                rs.getString("reviewed_by"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getString("rejection_reason"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getString("user_display_name"),
                rs.getString("phone_last_four")
        ), userId);
        if (rows.isEmpty()) {
            throw new ApiException("REVIEW_NOT_FOUND", "个人认证记录不存在", HttpStatus.NOT_FOUND);
        }
        return rows.getFirst();
    }

    /**
     * 获取企业认证详情。
     */
    public CompanyVerificationRow getCompanyVerificationDetail(UUID requestId) {
        List<CompanyVerificationRow> rows = jdbc.query("""
                SELECT cvr.id, cvr.applicant_user_id, cvr.company_id, cvr.request_type, cvr.legal_name, cvr.display_name,
                       cvr.credit_code_hash, cvr.credit_code_masked, cvr.license_reference, cvr.first_workspace_name,
                       cvr.status, cvr.reviewed_by, cvr.reviewed_at, cvr.rejection_reason, cvr.created_at,
                       u.display_name AS applicant_display_name
                FROM company_verification_requests cvr
                LEFT JOIN users u ON u.id = cvr.applicant_user_id
                WHERE cvr.id = ?
                """, (rs, n) -> new CompanyVerificationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("applicant_user_id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getString("request_type"),
                rs.getString("legal_name"),
                rs.getString("display_name"),
                rs.getString("credit_code_hash"),
                rs.getString("credit_code_masked"),
                rs.getString("license_reference"),
                rs.getString("first_workspace_name"),
                rs.getString("status"),
                rs.getString("reviewed_by"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getString("rejection_reason"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getString("applicant_display_name")
        ), requestId);
        if (rows.isEmpty()) {
            throw new ApiException("REVIEW_NOT_FOUND", "企业认证记录不存在", HttpStatus.NOT_FOUND);
        }
        return rows.getFirst();
    }

    /**
     * 获取企业加入申请详情。
     */
    public MembershipApplicationRow getMembershipApplicationDetail(UUID applicationId) {
        List<MembershipApplicationRow> rows = jdbc.query("""
                SELECT ma.id, ma.company_id, ma.applicant_user_id, ma.evidence, ma.status,
                       ma.reviewed_by_platform_user, ma.reviewed_at, ma.review_reason, ma.created_at,
                       u.display_name AS user_display_name,
                       c.display_name AS company_display_name
                FROM membership_applications ma
                LEFT JOIN users u ON u.id = ma.applicant_user_id
                LEFT JOIN companies c ON c.id = ma.company_id
                WHERE ma.id = ?
                """, (rs, n) -> new MembershipApplicationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class),
                rs.getObject("applicant_user_id", UUID.class),
                rs.getString("evidence"),
                rs.getString("status"),
                rs.getString("reviewed_by_platform_user"),
                rs.getTimestamp("reviewed_at") != null ? rs.getTimestamp("reviewed_at").toInstant() : null,
                rs.getString("review_reason"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getString("user_display_name"),
                rs.getString("company_display_name")
        ), applicationId);
        if (rows.isEmpty()) {
            throw new ApiException("REVIEW_NOT_FOUND", "企业加入申请不存在", HttpStatus.NOT_FOUND);
        }
        return rows.getFirst();
    }

    // ---- 数据记录 ----

    /**
     * 个人认证审核记录。
     */
    public record PersonalReviewRow(
            UUID id,
            UUID userId,
            String identityHash,
            String realNameMasked,
            String verificationStatus,
            String reviewedBy,
            Instant reviewedAt,
            String rejectionReason,
            Instant createdAt,
            String userDisplayName,
            String phoneLastFour
    ) {}

    /**
     * 企业认证审核记录。
     */
    public record CompanyVerificationRow(
            UUID id,
            UUID applicantUserId,
            UUID companyId,
            String requestType,
            String legalName,
            String displayName,
            String creditCodeHash,
            String creditCodeMasked,
            String licenseReference,
            String firstWorkspaceName,
            String status,
            String reviewedBy,
            Instant reviewedAt,
            String rejectionReason,
            Instant createdAt,
            String applicantDisplayName
    ) {}

    /**
     * 企业加入申请审核记录。
     */
    public record MembershipApplicationRow(
            UUID id,
            UUID companyId,
            UUID applicantUserId,
            String evidence,
            String status,
            String reviewedByPlatformUser,
            Instant reviewedAt,
            String reviewReason,
            Instant createdAt,
            String userDisplayName,
            String companyDisplayName
    ) {}

    /**
     * 分页结果封装。
     */
    public record PagedResult<T>(
            List<T> items,
            long total,
            int page,
            int size
    ) {}
}