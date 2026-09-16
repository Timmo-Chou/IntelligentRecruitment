package com.intelligentrecruitment.pools.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelligentrecruitment.candidates.application.PiiCipher;
import com.intelligentrecruitment.candidates.infrastructure.ResumeObjectStorage;
import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.boss.application.BossRequestContext;
import com.intelligentrecruitment.shared.error.ApiException;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService;
import com.intelligentrecruitment.tenancy.application.WorkspaceAccessService.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Immutable enterprise-pool copies. Source users can only change their own records, then re-sync the copy. */
@Service public class EnterprisePoolService {
  private final JdbcTemplate jdbc; private final WorkspaceAccessService access; private final PiiCipher pii; private final ResumeObjectStorage storage; private final BossControlPlaneClient boss; private final ObjectMapper objectMapper;
  public EnterprisePoolService(JdbcTemplate jdbc,WorkspaceAccessService access,PiiCipher pii,ResumeObjectStorage storage,BossControlPlaneClient boss,ObjectMapper objectMapper){this.jdbc=jdbc;this.access=access;this.pii=pii;this.storage=storage;this.boss=boss;this.objectMapper=objectMapper;}
  @Transactional public void syncCandidate(TenantScope scope,UUID ownerId,UUID candidateId){
    if(!scope.enterprise())return;
    if(!scope.talentPoolSharingEnabled()){jdbc.update("UPDATE enterprise_talent_pool_copies SET sync_status='PENDING_UPDATE' WHERE tenant_id=? AND source_candidate_id=? AND lifecycle_status='ACTIVE'",scope.tenantId(),candidateId);return;}
    int updated=jdbc.update("""
      INSERT INTO enterprise_talent_pool_copies(id,tenant_id,source_candidate_id,source_owner_user_id,snapshot,source_updated_at)
      SELECT ?,c.workspace_id,c.id,c.created_by,jsonb_build_object('display_name_masked',c.display_name_masked,'full_name_ciphertext',c.full_name_ciphertext,'email_ciphertext',c.email_ciphertext,'phone_ciphertext',c.phone_ciphertext,'profile',c.profile,'search_text',c.search_text,'status',c.status),c.updated_at
      FROM candidates c WHERE c.id=? AND c.workspace_id=? AND c.created_by=? AND c.status<>'DELETED'
      ON CONFLICT(tenant_id,source_candidate_id) DO UPDATE SET snapshot=EXCLUDED.snapshot,source_updated_at=EXCLUDED.source_updated_at,synchronized_at=CURRENT_TIMESTAMP,lifecycle_status='ACTIVE',deleted_at=NULL,sync_status='SYNCED'
      """,UUID.randomUUID(),candidateId,scope.tenantId(),ownerId);
    if(updated!=1)throw new ApiException("CANDIDATE_SOURCE_NOT_FOUND","人才源数据不存在或不属于当前账号",HttpStatus.NOT_FOUND);
    UUID copyId=jdbc.queryForObject("SELECT id FROM enterprise_talent_pool_copies WHERE tenant_id=? AND source_candidate_id=?",UUID.class,scope.tenantId(),candidateId);
    copyCandidateAttachments(scope.tenantId(),copyId,candidateId);
  }
  @Transactional public void syncAllCandidates(UUID userId,UUID tenantId){TenantScope s=access.requireBusinessAccess(userId,tenantId);if(!s.enterprise()||!s.talentPoolSharingEnabled())throw forbidden("企业人才池未开启共享");List<UUID> ids=jdbc.query("SELECT id FROM candidates WHERE workspace_id=? AND created_by=? AND status<>'DELETED'",(rs,n)->rs.getObject(1,UUID.class),tenantId,userId);for(UUID id:ids)syncCandidate(s,userId,id);}
  @Transactional public void syncJob(TenantScope scope,UUID ownerId,UUID jobId){
    if(!scope.enterprise())return;
    if(!scope.jobPoolSharingEnabled()){jdbc.update("UPDATE enterprise_job_pool_copies SET sync_status='PENDING_UPDATE' WHERE tenant_id=? AND source_job_id=? AND lifecycle_status='ACTIVE'",scope.tenantId(),jobId);return;}
    int updated=jdbc.update("""
      INSERT INTO enterprise_job_pool_copies(id,tenant_id,source_job_id,source_owner_user_id,snapshot,source_updated_at)
      SELECT ?,j.workspace_id,j.id,j.created_by,jsonb_build_object('title',j.title,'company_name',j.company_name,'location',j.location,'salary_range',j.salary_range,'description',j.description,'requirements',j.requirements,'skills',j.skills,'experience_level',j.experience_level,'education',j.education,'job_type',j.job_type,'status',j.status,'source',j.source),j.updated_at
      FROM jobs j WHERE j.id=? AND j.workspace_id=? AND j.created_by=? AND j.status<>'ARCHIVED'
      ON CONFLICT(tenant_id,source_job_id) DO UPDATE SET snapshot=EXCLUDED.snapshot,source_updated_at=EXCLUDED.source_updated_at,synchronized_at=CURRENT_TIMESTAMP,lifecycle_status='ACTIVE',deleted_at=NULL,sync_status='SYNCED'
      """,UUID.randomUUID(),jobId,scope.tenantId(),ownerId);
    if(updated!=1)throw new ApiException("JOB_SOURCE_NOT_FOUND","职位源数据不存在或不属于当前账号",HttpStatus.NOT_FOUND);
  }
  @Transactional public void syncAllJobs(UUID userId,UUID tenantId){TenantScope s=access.requireBusinessAccess(userId,tenantId);if(!s.enterprise()||!s.jobPoolSharingEnabled())throw forbidden("企业职位池未开启共享");List<UUID> ids=jdbc.query("SELECT id FROM jobs WHERE workspace_id=? AND created_by=? AND status<>'ARCHIVED'",(rs,n)->rs.getObject(1,UUID.class),tenantId,userId);for(UUID id:ids)syncJob(s,userId,id);}
  @Transactional(readOnly=true) public List<TalentCopy> talents(UUID userId,UUID tenantId){TenantScope s=access.requireEnterprisePoolReadAccess(userId,tenantId);if(!s.talentPoolSharingEnabled())throw forbidden("企业人才池未开启共享");requirePermission(userId,tenantId,"TALENT_POOL_VIEW");return jdbc.query("SELECT id,source_candidate_id,source_owner_user_id,snapshot->>'full_name_ciphertext',snapshot->>'email_ciphertext',snapshot->>'phone_ciphertext',snapshot->>'display_name_masked',(snapshot->'profile')::text,source_updated_at,synchronized_at,sync_status FROM enterprise_talent_pool_copies WHERE tenant_id=? AND lifecycle_status='ACTIVE' ORDER BY synchronized_at DESC",(rs,n)->{UUID copyId=rs.getObject(1,UUID.class);return new TalentCopy(copyId,rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),pii.decrypt(rs.getString(4)),pii.decrypt(rs.getString(5)),pii.decrypt(rs.getString(6)),rs.getString(7),rs.getString(8),rs.getTimestamp(9).toInstant(),rs.getTimestamp(10).toInstant(),rs.getString(11),attachments(copyId));},tenantId);}
  @Transactional(readOnly=true) public List<JobCopy> jobs(UUID userId,UUID tenantId){TenantScope s=access.requireEnterprisePoolReadAccess(userId,tenantId);if(!s.jobPoolSharingEnabled())throw forbidden("企业职位池未开启共享");requirePermission(userId,tenantId,"JOB_POOL_VIEW");return jdbc.query("SELECT id,source_job_id,source_owner_user_id,snapshot::text,source_updated_at,synchronized_at FROM enterprise_job_pool_copies WHERE tenant_id=? AND lifecycle_status='ACTIVE' ORDER BY synchronized_at DESC",(rs,n)->new JobCopy(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant()),tenantId);}
  @Transactional(readOnly=true) public PoolAccess poolAccess(UUID userId,UUID tenantId){TenantScope s=access.requireEnterprisePoolReadAccess(userId,tenantId);return new PoolAccess(s.talentPoolSharingEnabled(),s.jobPoolSharingEnabled(),s.talentPoolSharingEnabled()&&hasPermission(userId,tenantId,"TALENT_POOL_VIEW"),s.jobPoolSharingEnabled()&&hasPermission(userId,tenantId,"JOB_POOL_VIEW"));}
  private static ApiException forbidden(String m){return new ApiException("ENTERPRISE_POOL_SHARING_DISABLED",m,HttpStatus.FORBIDDEN);}
  private void copyCandidateAttachments(UUID tenantId,UUID copyId,UUID candidateId){
    List<SourceAttachment> source=jdbc.query("""
      SELECT f.id,f.object_key,f.original_filename,f.media_type,f.size_bytes,f.sha256
      FROM resume_files rf JOIN file_assets f ON f.id=rf.file_asset_id
      WHERE rf.candidate_id=? AND f.lifecycle_status='ACTIVE'
      """,(rs,n)->new SourceAttachment(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6)),candidateId);
    for(SourceAttachment item:source){
      Integer exists=jdbc.queryForObject("SELECT count(*) FROM enterprise_pool_attachment_assets WHERE tenant_id=? AND talent_pool_copy_id=? AND source_file_asset_id=? AND lifecycle_status='ACTIVE'",Integer.class,tenantId,copyId,item.sourceAssetId());
      if(exists!=null&&exists>0)continue;
      UUID assetId=UUID.randomUUID(); String objectKey=tenantId+"/enterprise-pool/"+assetId;
      storage.put(objectKey,storage.get(item.objectKey()),item.mediaType());
      jdbc.update("""
          INSERT INTO enterprise_pool_attachment_assets(id,tenant_id,talent_pool_copy_id,source_file_asset_id,object_key,original_filename,media_type,size_bytes,sha256,lifecycle_status)
          VALUES(?,?,?,?,?,?,?,?,?,'ACTIVE') ON CONFLICT(tenant_id,talent_pool_copy_id,source_file_asset_id) DO UPDATE SET object_key=EXCLUDED.object_key,original_filename=EXCLUDED.original_filename,media_type=EXCLUDED.media_type,size_bytes=EXCLUDED.size_bytes,sha256=EXCLUDED.sha256,lifecycle_status='ACTIVE',updated_at=CURRENT_TIMESTAMP""",
          assetId,tenantId,copyId,item.sourceAssetId(),objectKey,item.filename(),item.mediaType(),item.sizeBytes(),item.sha256());
    }
  }
  private List<Attachment> attachments(UUID copyId){return jdbc.query("SELECT id,original_filename,media_type,size_bytes FROM enterprise_pool_attachment_assets WHERE talent_pool_copy_id=? AND lifecycle_status='ACTIVE' ORDER BY created_at",(rs,n)->new Attachment(rs.getObject(1,UUID.class),pii.decryptIfEncrypted(rs.getString(2)),rs.getString(3),rs.getLong(4)),copyId);}
  public byte[] attachment(UUID userId,UUID tenantId,UUID copyId,UUID attachmentId){TenantScope s=access.requireEnterprisePoolReadAccess(userId,tenantId);if(!s.talentPoolSharingEnabled())throw forbidden("企业人才池未开启共享");requirePermission(userId,tenantId,"TALENT_POOL_VIEW");requirePermission(userId,tenantId,"CANDIDATE_ATTACHMENT_DOWNLOAD");List<String> keys=jdbc.query("SELECT object_key FROM enterprise_pool_attachment_assets WHERE id=? AND tenant_id=? AND talent_pool_copy_id=? AND lifecycle_status='ACTIVE'",(rs,n)->rs.getString(1),attachmentId,tenantId,copyId);if(keys.isEmpty())throw new ApiException("ENTERPRISE_POOL_ATTACHMENT_NOT_FOUND","企业池附件不存在",HttpStatus.NOT_FOUND);return storage.get(keys.getFirst());}
  @Transactional public byte[] exportTalents(UUID userId,UUID tenantId){TenantScope s=access.requireEnterprisePoolReadAccess(userId,tenantId);if(!s.talentPoolSharingEnabled())throw forbidden("企业人才池未开启共享");requirePermission(userId,tenantId,"TALENT_POOL_EXPORT");List<TalentCopy> rows=talents(userId,tenantId);StringBuilder csv=new StringBuilder("copy_id,source_candidate_id,source_owner_user_id,full_name,email,phone,masked_name,profile_json,synchronized_at\n");for(TalentCopy row:rows)csv.append(csv(row.copyId())).append(',').append(csv(row.sourceCandidateId())).append(',').append(csv(row.sourceOwnerUserId())).append(',').append(csv(row.fullName())).append(',').append(csv(row.email())).append(',').append(csv(row.phone())).append(',').append(csv(row.maskedName())).append(',').append(csv(row.profileJson())).append(',').append(csv(row.synchronizedAt())).append('\n');logExport(tenantId,"TALENT",userId,Map.of(),List.of("copy_id","source_candidate_id","source_owner_user_id","full_name","email","phone","masked_name","profile_json","synchronized_at"),rows.size());return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
  @Transactional public byte[] exportJobs(UUID userId,UUID tenantId){TenantScope s=access.requireEnterprisePoolReadAccess(userId,tenantId);if(!s.jobPoolSharingEnabled())throw forbidden("企业职位池未开启共享");requirePermission(userId,tenantId,"JOB_POOL_EXPORT");List<JobCopy> rows=jobs(userId,tenantId);StringBuilder csv=new StringBuilder("copy_id,source_job_id,source_owner_user_id,snapshot_json,source_updated_at,synchronized_at\n");for(JobCopy row:rows)csv.append(csv(row.copyId())).append(',').append(csv(row.sourceJobId())).append(',').append(csv(row.sourceOwnerUserId())).append(',').append(csv(row.snapshotJson())).append(',').append(csv(row.sourceUpdatedAt())).append(',').append(csv(row.synchronizedAt())).append('\n');logExport(tenantId,"JOB",userId,Map.of(),List.of("copy_id","source_job_id","source_owner_user_id","snapshot_json","source_updated_at","synchronized_at"),rows.size());return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
  private boolean hasPermission(UUID userId,UUID tenantId,String code){return boss.hasPermission(BossRequestContext.accessToken(userId),tenantId,code);}
  private void requirePermission(UUID userId,UUID tenantId,String code){if(!hasPermission(userId,tenantId,code))throw new ApiException("ENTERPRISE_POOL_PERMISSION_DENIED","当前账号没有企业池所需权限",HttpStatus.FORBIDDEN);}
  private void logExport(UUID tenantId,String type,UUID actor,Map<String,Object> filters,List<String> fields,int count){try{jdbc.update("INSERT INTO enterprise_pool_export_logs(id,tenant_id,pool_type,actor_user_id,filter_criteria,fields,record_count) VALUES(?,?,?,?,?::jsonb,?::jsonb,?)",UUID.randomUUID(),tenantId,type,actor,objectMapper.writeValueAsString(filters),objectMapper.writeValueAsString(fields),count);}catch(Exception e){throw new IllegalStateException("企业池导出审计记录失败",e);}}
  private static String csv(Object value){String s=value==null?"":String.valueOf(value);return "\""+s.replace("\"","\"\"")+"\"";}
  private record SourceAttachment(UUID sourceAssetId,String objectKey,String filename,String mediaType,long sizeBytes,String sha256){}
  public record Attachment(UUID id,String filename,String mediaType,long sizeBytes){}
  public record TalentCopy(UUID copyId,UUID sourceCandidateId,UUID sourceOwnerUserId,String fullName,String email,String phone,String maskedName,String profileJson,Instant sourceUpdatedAt,Instant synchronizedAt,String syncStatus,List<Attachment> attachments){}
  public record JobCopy(UUID copyId,UUID sourceJobId,UUID sourceOwnerUserId,String snapshotJson,Instant sourceUpdatedAt,Instant synchronizedAt){}
  public record PoolAccess(boolean talentPoolSharingEnabled,boolean jobPoolSharingEnabled,boolean talentPoolAllowed,boolean jobPoolAllowed){}
}
