package com.intelligentrecruitment.agentflow.application;

import com.intelligentrecruitment.agentflow.domain.*;
import com.intelligentrecruitment.aiplatform.application.*;
import com.intelligentrecruitment.aiplatform.domain.*;
import com.intelligentrecruitment.candidates.application.PiiCipher;
import com.intelligentrecruitment.tenancy.application.TenantAccessService.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecruitmentReleaseValidationService {
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final RecruitmentFlowCoordinator flow;
 private final RecruitmentExecutionMaintenance gate;private final AiPlatformClient ai;private final PiiCipher cipher;
 private final RecruitmentRouteResolver routes;private final String releaseSha;private final UUID instanceId=UUID.randomUUID();
 public RecruitmentReleaseValidationService(JdbcTemplate jdbc,ObjectMapper json,RecruitmentFlowCoordinator flow,RecruitmentExecutionMaintenance gate,AiPlatformClient ai,PiiCipher cipher,RecruitmentRouteResolver routes,@Value("${app.release.sha:unreleased}")String releaseSha){this.jdbc=jdbc;this.json=json;this.flow=flow;this.gate=gate;this.ai=ai;this.cipher=cipher;this.routes=routes;this.releaseSha=releaseSha;}
 @Scheduled(fixedDelayString="${app.release.instance-heartbeat-ms:30000}")
 public void registerInstance(){jdbc.update("INSERT INTO recruitment_execution_instances(id,route_config_version,release_sha,last_seen_at) VALUES(?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(id) DO UPDATE SET route_config_version=EXCLUDED.route_config_version,release_sha=EXCLUDED.release_sha,last_seen_at=CURRENT_TIMESTAMP",instanceId,routes.resolve(FlowCapability.JD_GENERATION).routeConfigVersion(),releaseSha);}
 @Transactional
 public UUID create(UUID cutover,UUID tenant,UUID actor,Map<String,Object> input){
   UUID id=UUID.randomUUID();
   ExecutionContext context=gate.validating(cutover,()->flow.createExecutionContext(flow.evaluateAuthoritative(FlowCapability.JD_GENERATION,new TenantScope(tenant,null,null,null),actor),id,"release-validation:"+id,"release-validation:"+cutover,List.of(),false));
   try{jdbc.update("INSERT INTO recruitment_release_validation_runs(id,cutover_id,tenant_id,actor_id,input,execution_context) VALUES(?,?,?,?,?::jsonb,?::jsonb)",id,cutover,tenant,actor,json.writeValueAsString(input),json.writeValueAsString(context));return id;}
   catch(Exception ex){throw new IllegalStateException(ex);}
 }
 @Scheduled(fixedDelayString="${app.release.validation-poll-ms:1000}")
 @Transactional
 public void processOne(){
   List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,tenant_id,actor_id,input::text AS input_json,execution_context::text AS context_json,ai_task_id FROM recruitment_release_validation_runs WHERE status IN ('CREATED','RUNNING') ORDER BY updated_at FOR UPDATE SKIP LOCKED LIMIT 1");
   if(rows.isEmpty())return;var row=rows.getFirst();UUID id=(UUID)row.get("id");
   try{
     ExecutionContext context=json.readValue((String)row.get("context_json"),ExecutionContext.class);
     AiTask task;
     if(row.get("ai_task_id")==null){task=ai.startTask(new StartAiTaskCommand(row.get("tenant_id").toString(),row.get("actor_id").toString(),id.toString(),context.idempotencyKey(),AiCapability.JD_GENERATION,json.readValue((String)row.get("input_json"),Map.class),context));}
     else task=ai.getTask((String)row.get("ai_task_id"),row.get("actor_id").toString());
     jdbc.update("UPDATE recruitment_release_validation_runs SET ai_task_id=?,status='RUNNING',updated_at=CURRENT_TIMESTAMP WHERE id=?",task.aiTaskId(),id);
     if(task.status()==AiTaskStatus.COMPLETED){var result=ai.getStructuredResult(task.aiTaskId(),row.get("actor_id").toString());
       new com.intelligentrecruitment.recruitment.application.JdStructuredResultMapper().toDraft(result);
       jdbc.update("UPDATE recruitment_release_validation_runs SET status='COMPLETED',result_ciphertext=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",cipher.encrypt(json.writeValueAsString(result)),id);
       ai.confirmResultPersisted(task.aiTaskId(),result);
     }else if(task.status()==AiTaskStatus.FAILED||task.status()==AiTaskStatus.CANCELLED){jdbc.update("UPDATE recruitment_release_validation_runs SET status='FAILED',error_code=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",task.errorCode(),id);ai.confirmNoBillableResult(task.aiTaskId(),"RELEASE_VALIDATION_FAILED");}
   }catch(Exception ex){jdbc.update("UPDATE recruitment_release_validation_runs SET error_code='VALIDATION_REQUIRES_RECONCILIATION',updated_at=CURRENT_TIMESTAMP WHERE id=?",id);}
 }
}
