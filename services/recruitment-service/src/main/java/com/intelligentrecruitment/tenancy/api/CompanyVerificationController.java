package com.intelligentrecruitment.tenancy.api;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 企业认证 BFF 控制器。
 * <p>
 * 对应前端 onboarding / settings 页面的企业认证流程：
 * <ul>
 *   <li>GET  /pending        查询待审核的企业认证申请</li>
 *   <li>POST /               提交企业认证申请</li>
 *   <li>POST /license-files  上传营业执照文件</li>
 * </ul>
 * 注意：该控制器路径为 /api/v1/company-verifications，与 TenancyController（/api/v1/companies）分离，
 * 因为前端调用路径不包含 /companies 前缀。
 */
@RestController
@RequestMapping("/api/v1/company-verifications")
public class CompanyVerificationController {

    private final BossControlPlaneClient boss;

    public CompanyVerificationController(BossControlPlaneClient boss) {
        this.boss = boss;
    }

    /**
     * 查询当前用户待审核的企业认证申请。
     * 返回 {legalName, displayName}，无待审核申请时返回 null。
     * displayName 用 legalName 代替（BOSS 不返回 displayName）。
     */
    @GetMapping("/pending")
    public PendingVerification pending(Authentication authentication) {
        List<BossControlPlaneClient.PendingRegistration> registrations =
                boss.pendingRegistrations(CurrentUser.bossAccessToken(authentication));
        if (registrations == null || registrations.isEmpty()) {
            return null;
        }
        BossControlPlaneClient.PendingRegistration first = registrations.get(0);
        return new PendingVerification(first.legalName(), first.legalName());
    }

    /**
     * 提交企业认证申请。
     * 请求体：{legalName, displayName, creditCode, licenseReference, firstWorkspaceName}
     * BOSS registerCompany 需要 contactName 和 contactPhone，前端未传：
     * - contactName 取 displayName（为空时用 legalName）
     * - contactPhone 暂用默认值（BOSS 从 access token 识别用户身份）
     * 返回 {id}（verificationRequestId）。
     */
    @PostMapping
    public VerificationResponse register(@Valid @RequestBody VerificationRequest request,
                                         Authentication authentication) {
        String token = CurrentUser.bossAccessToken(authentication);
        // contactName 优先用前端传入的 displayName，为空时用 legalName
        String contactName = (request.displayName() != null && !request.displayName().isBlank())
                ? request.displayName() : request.legalName();
        // contactPhone 暂用默认值，BOSS 通过 access token 识别用户
        String contactPhone = "13800000000";

        BossControlPlaneClient.Registration result = boss.registerCompany(
                token, request.legalName(), request.creditCode(),
                request.licenseReference(), contactName, contactPhone);
        return new VerificationResponse(result.verificationRequestId().toString());
    }

    /**
     * 上传营业执照文件。
     * 文件直接转发给 BOSS，Recruitment 不存储。
     * 返回 {reference, filename}。
     */
    @PostMapping(value = "/license-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LicenseFileResponse uploadLicense(@RequestPart("file") MultipartFile file,
                                             Authentication authentication) {
        BossControlPlaneClient.LicenseDocument result =
                boss.uploadLicenseDocument(CurrentUser.bossAccessToken(authentication), file);
        return new LicenseFileResponse(result.reference(), result.filename());
    }

    // ==================== 请求/响应 DTO ====================

    /** 待审核企业认证响应体 */
    public record PendingVerification(String legalName, String displayName) { }

    /** 企业认证请求体 */
    public record VerificationRequest(
            @NotBlank String legalName,
            String displayName,
            @NotBlank String creditCode,
            @NotBlank String licenseReference,
            String firstWorkspaceName) { }

    /** 企业认证响应体：返回 verificationRequestId */
    public record VerificationResponse(String id) { }

    /** 营业执照上传响应体 */
    public record LicenseFileResponse(String reference, String filename) { }
}
