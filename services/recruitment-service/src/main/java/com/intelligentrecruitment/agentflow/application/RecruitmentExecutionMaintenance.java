package com.intelligentrecruitment.agentflow.application;

import com.intelligentrecruitment.agentflow.domain.FlowCapability;
import com.intelligentrecruitment.shared.error.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class RecruitmentExecutionMaintenance {
 private static final Set<FlowCapability> OVERLAP=Set.of(FlowCapability.JD_GENERATION,FlowCapability.RESUME_PARSING,FlowCapability.CANDIDATE_SCREENING);
 private final JdbcTemplate jdbc;private final ObjectMapper json;private final com.intelligentrecruitment.boss.application.BossControlPlaneClient boss;
 private final ThreadLocal<UUID> validationCutover=new ThreadLocal<>();
 public RecruitmentExecutionMaintenance(JdbcTemplate jdbc,ObjectMapper json,com.intelligentrecruitment.boss.application.BossControlPlaneClient boss){this.jdbc=jdbc;this.json=json;this.boss=boss;}
 @Transactional(propagation=Propagation.MANDATORY)
 public void requireRootAllowed(FlowCapability capability,String routeVersion){
   Gate gate=jdbc.query("SELECT state,cutover_id,target_route_version,protocol_upgrade FROM recruitment_execution_maintenance WHERE id=1 "+(validationCutover.get()==null?"FOR SHARE":"FOR UPDATE"),(rs,n)->new Gate(rs.getString(1),rs.getObject(2,UUID.class),rs.getString(3),rs.getBoolean(4))).getFirst();
   if(!gate.protocolUpgrade&&!OVERLAP.contains(capability))return;
   if("OPEN".equals(gate.state)){
     if(gate.targetVersion!=null&&!gate.targetVersion.equals(routeVersion))throw denied();return;
   }
   if("VALIDATING".equals(gate.state)&&gate.cutover!=null&&gate.cutover.equals(validationCutover.get())&&routeVersion.equals(gate.targetVersion)){
     int n=jdbc.update("UPDATE recruitment_execution_maintenance SET validation_count=validation_count+1 WHERE id=1 AND validation_count<validation_limit");
     if(n==1)return;
   }
   throw denied();
 }
 public <T>T validating(UUID cutover,Supplier<T> action){
   if(validationCutover.get()!=null)throw new IllegalStateException("Nested validation scope");
   validationCutover.set(cutover);try{return action.get();}finally{validationCutover.remove();}
 }
 @Transactional
 public Map<String,Object> change(String state,UUID cutover,String version,boolean upgrade,Map<String,String> matrix){
   if(!Set.of("OPEN","DRAINING","VALIDATING").contains(state)||cutover==null||version==null||!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))throw new ApiException("MAINTENANCE_CONFIG_INVALID","发布门禁参数无效",HttpStatus.BAD_REQUEST);
   for(String repo:java.util.List.of("BOSS","AIAgentPlatform","IntelligentRecruitment"))if(matrix==null||matrix.get(repo)==null||!matrix.get(repo).matches("[0-9a-f]{40}"))throw new ApiException("VERSION_MATRIX_REQUIRED","必须提供三仓库完整版本矩阵",HttpStatus.BAD_REQUEST);
   String old=jdbc.queryForObject("SELECT state FROM recruitment_execution_maintenance WHERE id=1 FOR UPDATE",String.class);
   if("OPEN".equals(state)&&!"VALIDATING".equals(old))throw new ApiException("VALIDATION_REQUIRED","须先进入验证状态再开放",HttpStatus.CONFLICT);
   if("VALIDATING".equals(state)&&!"DRAINING".equals(old))throw new ApiException("DRAIN_REQUIRED","须先暂停新根入口",HttpStatus.CONFLICT);
   if(upgrade&&"VALIDATING".equals(state)){
     Integer active=jdbc.queryForObject("SELECT count(*) FROM ai_execution_records WHERE agent_id IS NULL AND status NOT IN ('SETTLED','RELEASED')",Integer.class);
     if(active==null||active>0||!boss.legacyExecutionsDrained())throw new ApiException("LEGACY_EXECUTIONS_PENDING","v1执行和结算尚未排空",HttpStatus.CONFLICT);
   }
   if("OPEN".equals(state)) {
     Integer successful=jdbc.queryForObject("SELECT count(*) FROM recruitment_release_validation_runs WHERE cutover_id=? AND status='COMPLETED'",Integer.class,cutover);
     Integer pending=jdbc.queryForObject("SELECT count(*) FROM recruitment_release_validation_runs v LEFT JOIN ai_execution_records e ON e.business_task_id=v.id::text AND e.tenant_id=v.tenant_id WHERE v.cutover_id=? AND (v.status<>'COMPLETED' OR e.status IS DISTINCT FROM 'SETTLED')",Integer.class,cutover);
     Integer mismatch=jdbc.queryForObject("SELECT count(*) FROM recruitment_execution_instances WHERE last_seen_at>CURRENT_TIMESTAMP-INTERVAL '2 minutes' AND (route_config_version<>? OR release_sha<>?)",Integer.class,version,matrix.get("IntelligentRecruitment"));
     Integer instances=jdbc.queryForObject("SELECT count(*) FROM recruitment_execution_instances WHERE last_seen_at>CURRENT_TIMESTAMP-INTERVAL '2 minutes'",Integer.class);
     if(successful==null||successful<1||pending==null||pending>0||mismatch==null||mismatch>0||instances==null||instances<1)throw new ApiException("CUTOVER_NOT_VERIFIED","验证任务或实例版本尚未一致",HttpStatus.CONFLICT);
   }
   try{
     String snapshot=json.writeValueAsString(matrix);
     jdbc.update("UPDATE recruitment_execution_maintenance SET state=?,cutover_id=?,target_route_version=?,protocol_upgrade=?,version_matrix=?::jsonb,validation_count=CASE WHEN cutover_id IS DISTINCT FROM ? THEN 0 ELSE validation_count END,updated_at=CURRENT_TIMESTAMP WHERE id=1",state,cutover,version,upgrade,snapshot,cutover);
     jdbc.update("INSERT INTO recruitment_maintenance_audits(id,cutover_id,old_state,new_state,target_route_version,version_matrix) VALUES(?,?,?,?,?,?::jsonb)",UUID.randomUUID(),cutover,old,state,version,snapshot);
     return jdbc.queryForMap("SELECT * FROM recruitment_execution_maintenance WHERE id=1");
   }catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}
 }
 private static ApiException denied(){return new ApiException("RECRUITMENT_EXECUTION_MAINTENANCE","招聘服务正在维护，请稍后重试",HttpStatus.SERVICE_UNAVAILABLE);}
 private record Gate(String state,UUID cutover,String targetVersion,boolean protocolUpgrade){}
}
