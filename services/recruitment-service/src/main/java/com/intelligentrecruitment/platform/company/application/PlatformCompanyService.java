package com.intelligentrecruitment.platform.company.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.shared.error.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** BOSS is the only source for recruitment enterprise and membership operations data. */
@Service
public class PlatformCompanyService {
    private final BossControlPlaneClient boss;
    public PlatformCompanyService(BossControlPlaneClient boss) { this.boss = boss; }
    public record CompanySummary(String tenantId,String companyName,String shortName,String verificationStatus,String managementStatus,int memberCount,String createdAt) {}
    public record CompanyDetail(String tenantId,String legalName,String displayName,String creditCodeMasked,String verificationStatus,String managementStatus,String ownerUserId,String ownerDisplayName,String licenseOriginalFilename,String licensePreviewUrl,List<MemberSummary> members,List<WorkspaceSummary> workspaces,String createdAt) {}
    public record MemberSummary(String userId,String displayName,String role,String status) {}
    /** Compatibility DTO: one recruitment Tenant replaces the deleted Workspace concept. */
    public record WorkspaceSummary(String workspaceId,String workspaceName,String status,int memberCount) {}
    public record PagedResult<T>(List<T> items,long total,int page,int pageSize) {}
    public PagedResult<CompanySummary> listCompanies(String search,String status,int page,int pageSize) {
        JsonNode root=boss.internalRecruitmentPlatformQuery("/tenants?search="+encode(search)+"&status="+encode(status)+"&page="+page+"&pageSize="+pageSize);
        List<CompanySummary> items=new java.util.ArrayList<>(); for(JsonNode item:root.path("items"))items.add(summary(item));
        return new PagedResult<>(items,root.path("total").asLong(),root.path("page").asInt(page),root.path("page_size").asInt(pageSize));
    }
    public CompanyDetail getCompanyDetail(UUID tenantId) {
        JsonNode root=boss.internalRecruitmentPlatformQuery("/tenants/detail?tenantId="+tenantId); JsonNode t=root.path("tenant");
        if(t.isMissingNode()||t.isEmpty())throw new ApiException("NOT_FOUND","企业不存在",HttpStatus.NOT_FOUND);
        List<MemberSummary> members=new java.util.ArrayList<>();for(JsonNode m:root.path("members"))members.add(new MemberSummary(text(m,"user_id"),text(m,"display_name"),text(m,"role"),text(m,"status")));
        return new CompanyDetail(text(t,"tenant_id"),text(t,"legal_name"),text(t,"display_name"),text(t,"credit_code"),"APPROVED",text(t,"management_status"),text(t,"owner_user_id"),text(t,"owner_display_name"),null,null,members,List.of(new WorkspaceSummary(text(t,"tenant_id"),text(t,"display_name"),text(t,"management_status"),members.size())),text(t,"created_at"));
    }
    private static CompanySummary summary(JsonNode x){return new CompanySummary(text(x,"tenant_id"),text(x,"legal_name"),text(x,"display_name"),text(x,"verification_status"),text(x,"management_status"),x.path("member_count").asInt(),text(x,"created_at"));}
    private static String text(JsonNode n,String key){return n.path(key).isNull()?null:n.path(key).asText(null);} private static String encode(String v){return java.net.URLEncoder.encode(v==null?"":v,java.nio.charset.StandardCharsets.UTF_8);}
}
