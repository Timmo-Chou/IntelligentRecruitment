package com.intelligentrecruitment.tenancy.application;

import com.intelligentrecruitment.billing.application.BillingService;
import com.intelligentrecruitment.notifications.application.NotificationService;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.shared.security.SecurityHashes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Service
public class TenancyService {
    private final JdbcTemplate jdbc;
    private final BillingService billing;
    private final NotificationService notifications;

    @Autowired
    public TenancyService(JdbcTemplate jdbc, BillingService billing, NotificationService notifications) {
        this.jdbc = jdbc;
        this.billing = billing;
        this.notifications = notifications;
    }

    /** Kept for isolated unit tests and callers that do not need notification side effects. */
    public TenancyService(JdbcTemplate jdbc, BillingService billing) {
        this(jdbc, billing, null);
    }

    private void emit(UUID userId, String type, String title, String content, String link) {
        if (notifications != null) notifications.create(userId, type, title, content, link);
    }

    @Transactional
    public WorkspaceView createPersonalWorkspace(UUID userId, String name) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM workspaces WHERE type = 'PERSONAL' AND owner_user_id = ? AND status = 'ACTIVE')
                """, Boolean.class, userId);
        if (Boolean.TRUE.equals(exists)) {
            throw new ApiException("PERSONAL_WORKSPACE_LIMIT", "MVP期间每个用户只能创建一个个人工作空间", HttpStatus.CONFLICT);
        }
        UUID workspaceId = createWorkspace(null, "PERSONAL", requiredName(name), userId, userId);
        billing.createAccount(workspaceId);
        audit(userId, null, workspaceId, "PERSONAL_WORKSPACE_CREATED", "WORKSPACE", workspaceId.toString());
        return workspace(workspaceId, true, userId);
    }

    @Transactional
    public UUID submitPersonalVerification(UUID userId, String realName, String identityNumber) {
        requirePersonalWorkspace(userId);
        String identityHash = SecurityHashes.sha256(required(identityNumber, "身份证明号码不能为空"));
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO personal_identities
                (id, user_id, identity_hash, real_name_masked, verification_status, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?)
                ON CONFLICT (user_id) DO UPDATE SET
                  identity_hash = EXCLUDED.identity_hash,
                  real_name_masked = EXCLUDED.real_name_masked,
                  verification_status = CASE WHEN personal_identities.verification_status = 'VERIFIED'
                    THEN personal_identities.verification_status ELSE 'PENDING' END,
                  reviewed_by = CASE WHEN personal_identities.verification_status = 'VERIFIED' THEN personal_identities.reviewed_by ELSE NULL END,
                  reviewed_at = CASE WHEN personal_identities.verification_status = 'VERIFIED' THEN personal_identities.reviewed_at ELSE NULL END,
                  rejection_reason = CASE WHEN personal_identities.verification_status = 'VERIFIED' THEN personal_identities.rejection_reason ELSE NULL END
                """, id, userId, identityHash, maskName(realName), timestamp(Instant.now()));
        audit(userId, null, null, "PERSONAL_VERIFICATION_SUBMITTED", "PERSONAL_IDENTITY", userId.toString());
        return id;
    }

    @Transactional
    public void approvePersonalVerification(UUID userId, String reviewer) {
        int updated = jdbc.update("""
                UPDATE personal_identities SET verification_status = 'VERIFIED', reviewed_by = ?, reviewed_at = ?, rejection_reason = NULL
                WHERE user_id = ? AND verification_status = 'PENDING'
                """, reviewer, timestamp(Instant.now()), userId);
        if (updated == 0) throw new ApiException("VERIFICATION_NOT_PENDING", "没有待审核的个人认证", HttpStatus.CONFLICT);
        UUID identityId = jdbc.queryForObject("SELECT id FROM personal_identities WHERE user_id = ?", UUID.class, userId);
        UUID workspaceId = requirePersonalWorkspace(userId);
        billing.grantTrial("PERSONAL_IDENTITY", identityId, "PERSONAL_TRIAL_V1", workspaceId, 3_000, userId);
        audit(null, null, workspaceId, "PERSONAL_VERIFICATION_APPROVED", "PERSONAL_IDENTITY", identityId.toString());
    }

    @Transactional
    public void rejectPersonalVerification(UUID userId, String reviewer, String reason) {
        int updated=jdbc.update("UPDATE personal_identities SET verification_status='REJECTED',reviewed_by=?,reviewed_at=?,rejection_reason=? WHERE user_id=? AND verification_status='PENDING'",reviewer,timestamp(Instant.now()),required(reason,"请填写拒绝原因"),userId);
        if(updated==0)throw new ApiException("VERIFICATION_NOT_PENDING","没有待审核的个人认证",HttpStatus.CONFLICT);
        audit(null,null,null,"PERSONAL_VERIFICATION_REJECTED","PERSONAL_IDENTITY",userId.toString());
    }

    @Transactional
    public UUID submitCompanyVerification(UUID userId, CompanyVerificationInput input) {
        String creditCode = required(input.creditCode(), "统一社会信用代码不能为空").toUpperCase();
        String creditHash = SecurityHashes.sha256(creditCode);
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM companies WHERE credit_code_hash = ?)",
                Boolean.class, creditHash);
        if (Boolean.TRUE.equals(exists)) {
            throw new ApiException("COMPANY_ALREADY_EXISTS", "该企业已存在，请申请加入或发起企业认领", HttpStatus.CONFLICT);
        }
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO company_verification_requests
                (id, applicant_user_id, request_type, legal_name, display_name, credit_code_hash, credit_code_masked,
                 license_reference, first_workspace_name, status, created_at)
                VALUES (?, ?, 'CREATE', ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """, requestId, userId, required(input.legalName(), "企业名称不能为空"),
                required(input.displayName(), "企业简称不能为空"), creditHash, maskCreditCode(creditCode),
                required(input.licenseReference(), "营业执照材料不能为空"),
                requiredName(input.firstWorkspaceName()), timestamp(Instant.now()));
        audit(userId, null, null, "COMPANY_VERIFICATION_SUBMITTED", "COMPANY_VERIFICATION", requestId.toString());
        emit(userId, "COMPANY_REVIEW", "企业申请已提交", "企业信息已提交平台审核，请耐心等待。", "/settings");
        return requestId;
    }

    @Transactional
    public CompanyApproval approveCompanyVerification(UUID requestId, String reviewer) {
        List<CompanyRequestRow> rows = jdbc.query("""
                SELECT applicant_user_id, company_id, request_type, legal_name, display_name,
                       credit_code_hash, credit_code_masked, first_workspace_name
                FROM company_verification_requests WHERE id = ? AND status = 'PENDING' FOR UPDATE
                """, (rs, n) -> new CompanyRequestRow(rs.getObject("applicant_user_id", UUID.class),
                        rs.getObject("company_id", UUID.class), rs.getString("request_type"), rs.getString("legal_name"),
                        rs.getString("display_name"), rs.getString("credit_code_hash"), rs.getString("credit_code_masked"),
                        rs.getString("first_workspace_name")), requestId);
        if (rows.isEmpty()) throw new ApiException("VERIFICATION_NOT_PENDING", "企业认证不在待审核状态", HttpStatus.CONFLICT);
        CompanyRequestRow request = rows.getFirst();
        if ("CLAIM".equals(request.requestType())) {
            return approveCompanyClaim(requestId, request, reviewer);
        }
        if ("UPDATE".equals(request.requestType())) {
            return approveCompanyUpdate(requestId, request, reviewer);
        }
        Boolean duplicate = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM companies WHERE credit_code_hash = ?)",
                Boolean.class, request.creditCodeHash());
        if (Boolean.TRUE.equals(duplicate)) {
            throw new ApiException("COMPANY_ALREADY_EXISTS", "企业已被创建，请转入认领流程", HttpStatus.CONFLICT);
        }
        Instant now = Instant.now();
        UUID companyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO companies
                (id, legal_name, display_name, credit_code_hash, credit_code_masked, verification_status,
                 management_status, owner_user_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'VERIFIED', 'USER_MANAGED', ?, ?, ?)
                """, companyId, request.legalName(), request.displayName(), request.creditCodeHash(),
                request.creditCodeMasked(), request.applicantUserId(), timestamp(now), timestamp(now));
        addCompanyMembership(companyId, request.applicantUserId(), "COMPANY_OWNER");
        UUID workspaceId = createWorkspace(companyId, "COMPANY", request.firstWorkspaceName(),
                request.applicantUserId(), request.applicantUserId());
        billing.createAccount(workspaceId);
        billing.grantTrial("COMPANY", companyId, "COMPANY_TRIAL_V1", workspaceId, 10_000, request.applicantUserId());
        jdbc.update("""
                UPDATE company_verification_requests SET company_id = ?, status = 'APPROVED', reviewed_by = ?, reviewed_at = ?
                WHERE id = ?
                """, companyId, reviewer, timestamp(now), requestId);
        audit(null, companyId, workspaceId, "COMPANY_VERIFICATION_APPROVED", "COMPANY", companyId.toString());
        return new CompanyApproval(companyId, workspaceId);
    }

    private CompanyApproval approveCompanyClaim(UUID requestId, CompanyRequestRow request, String reviewer) {
        List<UUID> owners = jdbc.query("SELECT owner_user_id FROM companies WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getObject("owner_user_id", UUID.class), request.companyId());
        if (owners.isEmpty()) throw new ApiException("COMPANY_NOT_FOUND", "企业不存在", HttpStatus.NOT_FOUND);
        if (owners.getFirst() != null) {
            throw new ApiException("COMPANY_ALREADY_CLAIMED", "该企业已有 Owner", HttpStatus.CONFLICT);
        }
        Instant now = Instant.now();
        jdbc.update("UPDATE companies SET owner_user_id=?, management_status='USER_MANAGED', updated_at=? WHERE id=?",
                request.applicantUserId(), timestamp(now), request.companyId());
        addCompanyMembership(request.companyId(), request.applicantUserId(), "COMPANY_OWNER");

        List<UUID> workspaceIds = jdbc.query("""
                SELECT id FROM workspaces WHERE company_id=? AND status='ACTIVE' ORDER BY created_at LIMIT 1
                """, (rs, n) -> rs.getObject("id", UUID.class), request.companyId());
        UUID workspaceId = workspaceIds.isEmpty() ? null : workspaceIds.getFirst();
        if (workspaceId == null) {
            workspaceId = createWorkspace(request.companyId(), "COMPANY", request.firstWorkspaceName(),
                    request.applicantUserId(), request.applicantUserId());
            billing.createAccount(workspaceId);
            billing.grantTrial("COMPANY", request.companyId(), "COMPANY_TRIAL_V1", workspaceId, 10_000,
                    request.applicantUserId());
        }
        jdbc.update("""
                UPDATE company_verification_requests SET status='APPROVED', reviewed_by=?, reviewed_at=? WHERE id=?
                """, reviewer, timestamp(now), requestId);
        audit(null, request.companyId(), workspaceId, "COMPANY_CLAIM_APPROVED", "COMPANY", request.companyId().toString());
        return new CompanyApproval(request.companyId(), workspaceId);
    }

    // 审核通过企业营业执照（统一社会信用代码）变更申请：仅更新执照信息，名称已在提交时即时生效
    private CompanyApproval approveCompanyUpdate(UUID requestId, CompanyRequestRow request, String reviewer) {
        if (request.companyId() == null) {
            throw new ApiException("COMPANY_NOT_FOUND", "申请未关联企业，无法更新营业执照", HttpStatus.CONFLICT);
        }
        Instant now = Instant.now();
        jdbc.update("""
                UPDATE companies SET credit_code_hash=?, credit_code_masked=?, updated_at=? WHERE id=?
                """, request.creditCodeHash(), request.creditCodeMasked(), timestamp(now), request.companyId());
        jdbc.update("UPDATE company_verification_requests SET status='APPROVED', reviewed_by=?, reviewed_at=? WHERE id=?",
                reviewer, timestamp(now), requestId);
        audit(null, request.companyId(), null, "COMPANY_LICENSE_CHANGE_APPROVED", "COMPANY_VERIFICATION", requestId.toString());
        return new CompanyApproval(request.companyId(), null);
    }

    // 企业信息更新：名称/简称即时生效；营业执照（信用代码）变更走平台审核
    @Transactional
    public CompanyUpdateResult updateCompany(UUID userId, UUID companyId, CompanyUpdateInput input) {
        requireCompanyAdmin(userId, companyId);
        Instant now = Instant.now();
        String displayName = required(input.displayName(), "企业简称不能为空");
        String legalName = required(input.legalName(), "企业名称不能为空");
        jdbc.update("UPDATE companies SET display_name=?, legal_name=?, updated_at=? WHERE id=?",
                displayName, legalName, timestamp(now), companyId);
        if (input.creditCode() != null && !input.creditCode().isBlank()) {
            String creditCode = input.creditCode().trim().toUpperCase();
            String creditHash = SecurityHashes.sha256(creditCode);
            String currentHash = jdbc.queryForObject("SELECT credit_code_hash FROM companies WHERE id=?", String.class, companyId);
            if (!creditHash.equals(currentHash)) {
                UUID requestId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO company_verification_requests
                        (id, applicant_user_id, company_id, request_type, legal_name, display_name,
                         credit_code_hash, credit_code_masked, license_reference, first_workspace_name, status, created_at)
                        VALUES (?, ?, ?, 'UPDATE', ?, ?, ?, ?, ?, '', 'PENDING', ?)
                        """, requestId, userId, companyId, legalName, displayName, creditHash,
                        maskCreditCode(creditCode), required(input.licenseReference(), "营业执照材料不能为空"),
                        timestamp(now));
                audit(userId, companyId, null, "COMPANY_LICENSE_CHANGE_SUBMITTED", "COMPANY_VERIFICATION", requestId.toString());
                return new CompanyUpdateResult(true, requestId.toString());
            }
        }
        return new CompanyUpdateResult(false, null);
    }

    @Transactional
    public void rejectCompanyVerification(UUID requestId,String reviewer,String reason){int updated=jdbc.update("""
            UPDATE company_verification_requests SET status='REJECTED',reviewed_by=?,reviewed_at=?,rejection_reason=?
            WHERE id=? AND status='PENDING'
            """,reviewer,timestamp(Instant.now()),required(reason,"请填写拒绝原因"),requestId);if(updated==0)throw new ApiException("VERIFICATION_NOT_PENDING","企业认证不在待审核状态",HttpStatus.CONFLICT);audit(null,null,null,"COMPANY_VERIFICATION_REJECTED","COMPANY_VERIFICATION",requestId.toString());}

    @Transactional
    public WorkspaceView createCompanyWorkspace(UUID userId, UUID companyId, String name, UUID ownerUserId) {
        requireCompanyAdmin(userId, companyId);
        requireCompanyMember(ownerUserId, companyId);
        UUID workspaceId = createWorkspace(companyId, "COMPANY", requiredName(name), ownerUserId, userId);
        billing.createAccount(workspaceId);
        audit(userId, companyId, workspaceId, "COMPANY_WORKSPACE_CREATED", "WORKSPACE", workspaceId.toString());
        return workspace(workspaceId, userId.equals(ownerUserId), userId);
    }

    public List<WorkspaceView> listWorkspaces(UUID userId) {
        Map<UUID, WorkspaceView> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT w.id, w.company_id, w.type, w.name, w.owner_user_id, w.status, true AS has_data_access, wm.role AS current_role,
                  (SELECT count(*) FROM workspace_memberships wm2 WHERE wm2.workspace_id=w.id AND wm2.status='ACTIVE') member_count
                FROM workspaces w JOIN workspace_memberships wm ON wm.workspace_id=w.id
                WHERE wm.user_id=? AND wm.status='ACTIVE'
                ORDER BY w.created_at
                """, (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> result.put(rs.getObject("id", UUID.class), workspace(rs)), userId);
        jdbc.query("""
                SELECT w.id, w.company_id, w.type, w.name, w.owner_user_id, w.status, false AS has_data_access, NULL::varchar AS current_role,
                  (SELECT count(*) FROM workspace_memberships wm2 WHERE wm2.workspace_id=w.id AND wm2.status='ACTIVE') member_count
                FROM workspaces w JOIN company_memberships cm ON cm.company_id=w.company_id
                WHERE cm.user_id=? AND cm.status='ACTIVE' AND cm.role IN ('COMPANY_OWNER','COMPANY_ADMIN')
                ORDER BY w.created_at
                """, (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> result.putIfAbsent(rs.getObject("id", UUID.class), workspace(rs)), userId);
        return List.copyOf(result.values());
    }

    public List<CompanyView> listCompanies(UUID userId) {
        return jdbc.query("""
                SELECT c.id, c.display_name, c.legal_name, c.credit_code_masked, c.verification_status, cm.role
                FROM companies c JOIN company_memberships cm ON cm.company_id=c.id
                WHERE cm.user_id=? AND cm.status='ACTIVE' ORDER BY c.created_at
                """, (rs, n) -> new CompanyView(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("legal_name"), rs.getString("credit_code_masked"),
                        rs.getString("verification_status"), rs.getString("role")), userId);
    }

    public CompanyPendingView pendingCompanyVerification(UUID userId) {
        List<CompanyPendingView> rows = jdbc.query("SELECT id, legal_name, display_name, created_at FROM company_verification_requests WHERE applicant_user_id=? AND request_type='CREATE' AND status='PENDING' ORDER BY created_at DESC LIMIT 1",
                (rs, n) -> new CompanyPendingView(rs.getObject("id", UUID.class), rs.getString("legal_name"), rs.getString("display_name"), rs.getTimestamp("created_at").toInstant()), userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<CompanySearchResult> searchCompanies(String query) {
        String q = "%" + query.trim() + "%";
        return jdbc.query("""
                SELECT c.id, c.display_name, c.legal_name, c.verification_status,
                  (SELECT count(*) FROM company_memberships cm WHERE cm.company_id=c.id AND cm.status='ACTIVE') member_count
                FROM companies c
                WHERE c.verification_status = 'VERIFIED'
                  AND (c.display_name ILIKE ? OR c.legal_name ILIKE ?)
                ORDER BY c.created_at DESC
                LIMIT 20
                """, (rs, n) -> new CompanySearchResult(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("legal_name"),
                        rs.getString("verification_status"),
                        rs.getInt("member_count")),
                q, q);
    }

    @Transactional
    public UUID applyToCompany(UUID userId, UUID companyId, String evidence) {
        ensureCompany(companyId);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO membership_applications
                (id, company_id, applicant_user_id, evidence, status, created_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?) ON CONFLICT DO NOTHING
                """, id, companyId, userId, required(evidence, "请填写与企业关系的证明说明"), timestamp(Instant.now()));
        if (inserted == 0) throw new ApiException("APPLICATION_ALREADY_PENDING", "已有待审核的加入申请", HttpStatus.CONFLICT);
        emit(userId, "MEMBERSHIP_REVIEW", "加入申请已提交", "加入申请已提交，请等待企业 Owner 或管理员审核。", "/settings");
        jdbc.query("SELECT user_id FROM company_memberships WHERE company_id=? AND status='ACTIVE' AND role IN ('COMPANY_OWNER','COMPANY_ADMIN') AND user_id<>?", (rs, n) -> rs.getObject("user_id", UUID.class), companyId, userId)
                .forEach(adminId -> emit(adminId, "MEMBERSHIP_REVIEW", "新的企业加入申请", "有用户申请加入你的企业，请前往组织与工作空间处理。", "/settings"));
        return id;
    }

    public List<MembershipApplicationView> listCompanyApplications(UUID userId, UUID companyId) {
        requireCompanyAdmin(userId, companyId);
        return jdbc.query("""
                SELECT ma.id, ma.applicant_user_id, u.phone_last_four, ma.evidence, ma.status, ma.created_at
                FROM membership_applications ma JOIN users u ON u.id = ma.applicant_user_id
                WHERE ma.company_id=? ORDER BY ma.created_at DESC
                """, (rs, n) -> new MembershipApplicationView(rs.getObject("id", UUID.class),
                rs.getObject("applicant_user_id", UUID.class), rs.getString("phone_last_four"),
                rs.getString("evidence"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()), companyId);
    }

    @Transactional
    public void approveCompanyApplication(UUID userId, UUID applicationId) {
        ApplicationRow application = pendingApplication(applicationId);
        requireCompanyAdmin(userId, application.companyId());
        addCompanyMembership(application.companyId(), application.userId(), "COMPANY_MEMBER");
        jdbc.update("UPDATE membership_applications SET status='APPROVED', reviewed_by_user_id=?, reviewed_at=? WHERE id=?",
                userId, timestamp(Instant.now()), applicationId);
        emit(application.userId(), "MEMBERSHIP_REVIEW", "加入企业申请已通过", "你的企业加入申请已通过，现在可以使用企业工作台。", "/");
        audit(userId, application.companyId(), null, "COMPANY_MEMBERSHIP_APPROVED", "MEMBERSHIP_APPLICATION", applicationId.toString());
    }

    @Transactional
    public void rejectCompanyApplication(UUID userId, UUID applicationId, String reason) {
        ApplicationRow application = pendingApplication(applicationId);
        requireCompanyAdmin(userId, application.companyId());
        int updated = jdbc.update("UPDATE membership_applications SET status='REJECTED', reviewed_by_user_id=?, reviewed_at=?, review_reason=? WHERE id=? AND status='PENDING'",
                userId, timestamp(Instant.now()), required(reason, "请填写拒绝原因"), applicationId);
        if (updated == 0) throw new ApiException("APPLICATION_NOT_PENDING", "申请不在待审核状态", HttpStatus.CONFLICT);
        emit(application.userId(), "MEMBERSHIP_REVIEW", "加入企业申请未通过", "你的企业加入申请未通过，请联系企业 Owner 或管理员。", "/settings");
        audit(userId, application.companyId(), null, "COMPANY_MEMBERSHIP_REJECTED", "MEMBERSHIP_APPLICATION", applicationId.toString());
    }

    private ApplicationRow pendingApplication(UUID applicationId) {
        List<ApplicationRow> rows = jdbc.query("SELECT company_id, applicant_user_id FROM membership_applications WHERE id=? AND status='PENDING' FOR UPDATE",
                (rs, n) -> new ApplicationRow(rs.getObject("company_id", UUID.class), rs.getObject("applicant_user_id", UUID.class)), applicationId);
        if (rows.isEmpty()) throw new ApiException("APPLICATION_NOT_PENDING", "申请不在待审核状态", HttpStatus.CONFLICT);
        return rows.getFirst();
    }

    @Transactional
    public void approveCompanyApplication(UUID applicationId, String reviewer) {
        List<ApplicationRow> applications = jdbc.query("""
                SELECT company_id, applicant_user_id FROM membership_applications
                WHERE id=? AND status='PENDING' FOR UPDATE
                """, (rs, n) -> new ApplicationRow(rs.getObject("company_id", UUID.class),
                rs.getObject("applicant_user_id", UUID.class)), applicationId);
        if (applications.isEmpty()) throw new ApiException("APPLICATION_NOT_PENDING", "申请不在待审核状态", HttpStatus.CONFLICT);
        ApplicationRow application = applications.getFirst();
        addCompanyMembership(application.companyId(), application.userId(), "COMPANY_MEMBER");
        jdbc.update("""
                UPDATE membership_applications SET status='APPROVED', reviewed_by_platform_user=?, reviewed_at=? WHERE id=?
                """, reviewer, timestamp(Instant.now()), applicationId);
        audit(null, application.companyId(), null, "COMPANY_MEMBERSHIP_APPROVED", "MEMBERSHIP_APPLICATION", applicationId.toString());
    }

    @Transactional
    public void rejectCompanyApplication(UUID applicationId,String reviewer,String reason){int updated=jdbc.update("""
            UPDATE membership_applications SET status='REJECTED',reviewed_by_platform_user=?,reviewed_at=?,review_reason=?
            WHERE id=? AND status='PENDING'
            """,reviewer,timestamp(Instant.now()),required(reason,"请填写拒绝原因"),applicationId);if(updated==0)throw new ApiException("APPLICATION_NOT_PENDING","申请不在待审核状态",HttpStatus.CONFLICT);audit(null,null,null,"COMPANY_MEMBERSHIP_REJECTED","MEMBERSHIP_APPLICATION",applicationId.toString());}

    @Transactional
    public Invitation createCompanyInvitation(UUID userId, UUID companyId, String phone, String role) {
        requireCompanyAdmin(userId, companyId);
        if (!List.of("COMPANY_ADMIN", "COMPANY_MEMBER").contains(role)) {
            throw new ApiException("INVALID_ROLE", "企业邀请角色不合法", HttpStatus.BAD_REQUEST);
        }
        return createInvitation(userId, "COMPANY", companyId, phone, role);
    }

    @Transactional
    public Invitation createWorkspaceInvitation(UUID userId, UUID workspaceId, String phone, String role) {
        requireWorkspaceAdmin(userId, workspaceId);
        if (!List.of("WORKSPACE_ADMIN", "RECRUITER").contains(role)) {
            throw new ApiException("INVALID_ROLE", "工作空间邀请角色不合法", HttpStatus.BAD_REQUEST);
        }
        return createInvitation(userId, "WORKSPACE", workspaceId, phone, role);
    }

    @Transactional
    public void acceptInvitation(UUID userId, String token) {
        String phoneHash = jdbc.queryForObject("SELECT phone_hash FROM users WHERE id=?", String.class, userId);
        List<InvitationRow> rows = jdbc.query("""
                SELECT id, target_type, target_id, role, phone_hash FROM membership_invitations
                WHERE token_hash=? AND status='PENDING' AND expires_at>? FOR UPDATE
                """, (rs, n) -> new InvitationRow(rs.getObject("id", UUID.class), rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class), rs.getString("role"), rs.getString("phone_hash")),
                SecurityHashes.sha256(token), timestamp(Instant.now()));
        if (rows.isEmpty() || !rows.getFirst().phoneHash().equals(phoneHash)) {
            throw new ApiException("INVITATION_INVALID", "邀请不存在、已过期或与当前手机号不匹配", HttpStatus.BAD_REQUEST);
        }
        InvitationRow invite = rows.getFirst();
        if (invite.targetType().equals("COMPANY")) {
            addCompanyMembership(invite.targetId(), userId, invite.role());
        } else {
            UUID companyId = jdbc.queryForObject("SELECT company_id FROM workspaces WHERE id=?", UUID.class, invite.targetId());
            if (companyId != null) requireCompanyMember(userId, companyId);
            addWorkspaceMembership(invite.targetId(), userId, invite.role());
        }
        jdbc.update("UPDATE membership_invitations SET status='ACCEPTED', accepted_by=?, accepted_at=? WHERE id=?",
                userId, timestamp(Instant.now()), invite.id());
    }

    private Invitation createInvitation(UUID userId, String targetType, UUID targetId, String phone, String role) {
        String normalized = phone == null ? "" : phone.replaceAll("\\s+", "");
        if (!normalized.matches("1\\d{10}")) throw new ApiException("INVALID_PHONE", "请输入正确的11位手机号", HttpStatus.BAD_REQUEST);
        UUID id = UUID.randomUUID();
        String token = SecurityHashes.randomToken();
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO membership_invitations
                (id, target_type, target_id, phone_hash, role, token_hash, expires_at, status, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, id, targetType, targetId, SecurityHashes.sha256(normalized), role,
                SecurityHashes.sha256(token), timestamp(expiresAt), userId, timestamp(Instant.now()));
        return new Invitation(id, token, expiresAt);
    }

    private UUID createWorkspace(UUID companyId, String type, String name, UUID ownerUserId, UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO workspaces (id, company_id, type, name, owner_user_id, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, id, companyId, type, name, ownerUserId, createdBy, timestamp(now), timestamp(now));
        addWorkspaceMembership(id, ownerUserId, "WORKSPACE_OWNER");
        return id;
    }

    private void addCompanyMembership(UUID companyId, UUID userId, String role) {
        jdbc.update("""
                INSERT INTO company_memberships (id, company_id, user_id, role, status, joined_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (company_id, user_id) DO UPDATE SET role=EXCLUDED.role, status='ACTIVE'
                """, UUID.randomUUID(), companyId, userId, role, timestamp(Instant.now()));
    }

    private void addWorkspaceMembership(UUID workspaceId, UUID userId, String role) {
        jdbc.update("""
                INSERT INTO workspace_memberships (id, workspace_id, user_id, role, status, joined_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (workspace_id, user_id) DO UPDATE SET role=EXCLUDED.role, status='ACTIVE'
                """, UUID.randomUUID(), workspaceId, userId, role, timestamp(Instant.now()));
    }

    private UUID requirePersonalWorkspace(UUID userId) {
        List<UUID> workspaces = jdbc.query("""
                SELECT id FROM workspaces WHERE type='PERSONAL' AND owner_user_id=? AND status='ACTIVE'
                """, (rs, n) -> rs.getObject("id", UUID.class), userId);
        if (workspaces.isEmpty()) throw new ApiException("PERSONAL_WORKSPACE_REQUIRED", "请先创建个人工作空间", HttpStatus.CONFLICT);
        return workspaces.getFirst();
    }

    private void requireCompanyAdmin(UUID userId, UUID companyId) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM company_memberships WHERE company_id=? AND user_id=?
                  AND status='ACTIVE' AND role IN ('COMPANY_OWNER','COMPANY_ADMIN'))
                """, Boolean.class, companyId, userId);
        if (!Boolean.TRUE.equals(allowed)) throw notFound();
    }

    private void requireCompanyMember(UUID userId, UUID companyId) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM company_memberships WHERE company_id=? AND user_id=? AND status='ACTIVE')
                """, Boolean.class, companyId, userId);
        if (!Boolean.TRUE.equals(allowed)) throw new ApiException("COMPANY_MEMBER_REQUIRED", "指定用户不是企业成员", HttpStatus.CONFLICT);
    }

    private void requireWorkspaceAdmin(UUID userId, UUID workspaceId) {
        Boolean allowed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM workspace_memberships WHERE workspace_id=? AND user_id=?
                  AND status='ACTIVE' AND role IN ('WORKSPACE_OWNER','WORKSPACE_ADMIN'))
                """, Boolean.class, workspaceId, userId);
        if (!Boolean.TRUE.equals(allowed)) throw notFound();
    }

    private void ensureCompany(UUID companyId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM companies WHERE id=? AND verification_status='VERIFIED')",
                Boolean.class, companyId);
        if (!Boolean.TRUE.equals(exists)) throw new ApiException("COMPANY_NOT_FOUND", "企业不存在", HttpStatus.NOT_FOUND);
    }

    private WorkspaceView workspace(UUID workspaceId, boolean hasDataAccess,UUID viewerUserId) {
        return jdbc.queryForObject("""
                SELECT w.id, w.company_id, w.type, w.name, w.owner_user_id, w.status, ? AS has_data_access,
                  (SELECT wm.role FROM workspace_memberships wm WHERE wm.workspace_id=w.id AND wm.user_id=? AND wm.status='ACTIVE') current_role,
                  (SELECT count(*) FROM workspace_memberships wm WHERE wm.workspace_id=w.id AND wm.status='ACTIVE') member_count
                FROM workspaces w WHERE w.id=?
                """, (rs, n) -> workspace(rs), hasDataAccess,viewerUserId,workspaceId);
    }

    private static WorkspaceView workspace(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkspaceView(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("type"), rs.getString("name"), rs.getObject("owner_user_id", UUID.class),
                rs.getString("status"), rs.getInt("member_count"), rs.getBoolean("has_data_access"),rs.getString("current_role"));
    }

    private void audit(UUID actor, UUID companyId, UUID workspaceId, String action, String type, String resourceId) {
        jdbc.update("""
                INSERT INTO audit_logs (id, actor_user_id, company_id, workspace_id, action, resource_type, resource_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor, companyId, workspaceId, action, type, resourceId, timestamp(Instant.now()));
    }

    private static ApiException notFound() {
        return new ApiException("RESOURCE_NOT_FOUND", "资源不存在或无权访问", HttpStatus.NOT_FOUND);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new ApiException("VALIDATION_FAILED", message, HttpStatus.BAD_REQUEST);
        return value.trim();
    }
    private static String requiredName(String value) {
        String result = required(value, "工作空间名称不能为空");
        if (result.length() > 120) throw new ApiException("VALIDATION_FAILED", "工作空间名称不能超过120个字符", HttpStatus.BAD_REQUEST);
        return result;
    }
    private static String maskName(String name) {
        String value = required(name, "姓名不能为空");
        return value.length() == 1 ? "*" : value.substring(0, 1) + "*".repeat(Math.min(3, value.length() - 1));
    }
    private static String maskCreditCode(String code) {
        if (code.length() <= 8) return "****";
        return code.substring(0, 4) + "**********" + code.substring(code.length() - 4);
    }

    public record CompanyVerificationInput(String legalName, String displayName, String creditCode,
                                           String licenseReference, String firstWorkspaceName) {}
    public record CompanyUpdateInput(String displayName, String legalName, String creditCode,
                                     String licenseReference) {}
    public record CompanyUpdateResult(boolean licenseChangeSubmitted, String licenseChangeRequestId) {}
    public record CompanyApproval(UUID companyId, UUID workspaceId) {}
    public record WorkspaceView(UUID id, UUID companyId, String type, String name, UUID ownerUserId,
                                String status, int memberCount, boolean hasDataAccess,String currentRole) {}
    public record CompanyView(UUID id, String displayName, String legalName, String creditCodeMasked,
                              String verificationStatus, String role) {}
    public record CompanyPendingView(UUID id, String legalName, String displayName, Instant createdAt) {}
    public record CompanySearchResult(UUID id, String displayName, String legalName, String verificationStatus, int memberCount) {}
    public record MembershipApplicationView(UUID id, UUID applicantUserId, String applicantPhone, String evidence, String status, Instant createdAt) {}
    public record Invitation(UUID id, String token, Instant expiresAt) {}
    private record CompanyRequestRow(UUID applicantUserId, UUID companyId, String requestType,
                                     String legalName, String displayName,
                                     String creditCodeHash, String creditCodeMasked, String firstWorkspaceName) {}
    private record ApplicationRow(UUID companyId, UUID userId) {}
    private record InvitationRow(UUID id, String targetType, UUID targetId, String role, String phoneHash) {}
}
