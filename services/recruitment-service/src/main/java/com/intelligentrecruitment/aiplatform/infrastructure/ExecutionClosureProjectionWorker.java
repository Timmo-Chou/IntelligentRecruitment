package com.intelligentrecruitment.aiplatform.infrastructure;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;
/** Projects an already-committed deadline close; it never picks a second financial decision. */
@Component
public class ExecutionClosureProjectionWorker {
 private final JdbcTemplate jdbc;public ExecutionClosureProjectionWorker(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @Scheduled(fixedDelayString="${app.ai-platform.close-projection-ms:1000}")
 public void project(){
  jdbc.update("UPDATE ai_runs r SET status='FAILED',progress=100,error_code='OUTCOME_UNVERIFIABLE',completed_at=CURRENT_TIMESTAMP FROM ai_execution_records e WHERE r.provider_task_id=e.agent_task_id AND r.tenant_id=e.tenant_id AND r.status IN ('QUEUED','RUNNING') AND e.final_decision='RELEASE' AND e.billing_reason_code='RECONCILIATION_DEADLINE_EXPIRED'");
  jdbc.update("UPDATE resume_files r SET status='FAILED',error_code='OUTCOME_UNVERIFIABLE',updated_at=CURRENT_TIMESTAMP FROM ai_execution_records e WHERE r.provider_task_id::text=e.agent_task_id AND r.tenant_id=e.tenant_id AND r.status IN ('QUEUED','PROCESSING') AND e.final_decision='RELEASE' AND e.billing_reason_code='RECONCILIATION_DEADLINE_EXPIRED'");
  jdbc.update("UPDATE screening_run_items r SET status='FAILED',error_code='OUTCOME_UNVERIFIABLE',result_validity='UNVERIFIED_RESULT',billable_unit_count=0,billing_reason_code='RECONCILIATION_DEADLINE_EXPIRED',updated_at=CURRENT_TIMESTAMP FROM ai_execution_records e WHERE r.provider_task_id=e.agent_task_id AND r.tenant_id=e.tenant_id AND r.status IN ('PROCESSING','RECONCILIATION_REQUIRED') AND e.final_decision='RELEASE' AND e.billing_reason_code='RECONCILIATION_DEADLINE_EXPIRED'");
  jdbc.update("UPDATE screening_execution_batches b SET status='FAILED',updated_at=CURRENT_TIMESTAMP FROM ai_execution_records e WHERE b.provider_task_id=e.agent_task_id AND b.tenant_id=e.tenant_id AND b.status IN ('PROCESSING','RECONCILIATION_REQUIRED') AND e.final_decision='RELEASE' AND e.billing_reason_code='RECONCILIATION_DEADLINE_EXPIRED'");
 }
}
