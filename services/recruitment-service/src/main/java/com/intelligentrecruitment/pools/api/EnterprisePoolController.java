package com.intelligentrecruitment.pools.api;
import com.intelligentrecruitment.pools.application.EnterprisePoolService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController @RequestMapping("/api/v1/tenants/{tenantId}/enterprise-pools") public class EnterprisePoolController {
 private final EnterprisePoolService pools; public EnterprisePoolController(EnterprisePoolService pools){this.pools=pools;}
 @GetMapping("/access") EnterprisePoolService.PoolAccess access(@PathVariable UUID tenantId,Authentication a){return pools.poolAccess(CurrentUser.id(a),tenantId);}
 @GetMapping("/talents") List<EnterprisePoolService.TalentCopy> talents(@PathVariable UUID tenantId,Authentication a){return pools.talents(CurrentUser.id(a),tenantId);}
 @PostMapping("/talents/sync") void syncTalents(@PathVariable UUID tenantId,Authentication a){pools.syncAllCandidates(CurrentUser.id(a),tenantId);}
 @GetMapping("/jobs") List<EnterprisePoolService.JobCopy> jobs(@PathVariable UUID tenantId,Authentication a){return pools.jobs(CurrentUser.id(a),tenantId);}
 @PostMapping("/jobs/sync") void syncJobs(@PathVariable UUID tenantId,Authentication a){pools.syncAllJobs(CurrentUser.id(a),tenantId);}
 @GetMapping(value="/talents/export",produces="text/csv") ResponseEntity<byte[]> exportTalents(@PathVariable UUID tenantId,Authentication a){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=enterprise-talents.csv").body(pools.exportTalents(CurrentUser.id(a),tenantId));}
 @GetMapping(value="/jobs/export",produces="text/csv") ResponseEntity<byte[]> exportJobs(@PathVariable UUID tenantId,Authentication a){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=enterprise-jobs.csv").body(pools.exportJobs(CurrentUser.id(a),tenantId));}
 @GetMapping("/talents/{copyId}/attachments/{attachmentId}") ResponseEntity<byte[]> attachment(@PathVariable UUID tenantId,@PathVariable UUID copyId,@PathVariable UUID attachmentId,Authentication a){return ResponseEntity.ok().body(pools.attachment(CurrentUser.id(a),tenantId,copyId,attachmentId));}
}
