package com.intelligentrecruitment.aiplatform.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Reliable projection of IR's final reconciliation close into AEP. */
@Component
public class PlatformExecutionClosureWorker {
    private final JdbcTemplate jdbc;private final RestClient client;private final String baseUrl,token;
    public PlatformExecutionClosureWorker(JdbcTemplate jdbc,@Value("${app.ai-platform.http.base-url:http://localhost:8083}") String baseUrl,
            @Value("${app.ai-platform.http.service-token:}") String token,
            @Value("${app.ai-platform.http.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.ai-platform.http.read-timeout-ms:15000}") int readTimeoutMs){
        this.jdbc=jdbc;this.baseUrl=baseUrl.replaceAll("/+$","");this.token=token;
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();factory.setConnectTimeout(connectTimeoutMs);factory.setReadTimeout(readTimeoutMs);
        this.client=RestClient.builder().requestFactory(factory).build();
    }
    @Scheduled(fixedDelayString="${app.ai-platform.close-outbox-poll-ms:1000}")
    public void dispatch(){
        for(int i=0;i<20;i++){
            Close row=claim();if(row==null)return;
            try{
                client.post().uri(baseUrl+"/api/v1/internal/tasks/"+row.taskId()+"/close-unverifiable")
                        .header("Authorization","Bearer "+token).body(new CloseRequest(row.closureId(),row.tenantId(),row.attemptId(),row.agentId(),row.operation(),row.routeVersion(),row.inputHash(),row.expectedVersion()))
                        .retrieve().body(JsonNode.class);
                jdbc.update("UPDATE ai_execution_close_outbox SET status='DELIVERED',locked_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",row.id());
            }catch(Exception ex){
                long delay=Math.min(3600,1L<<Math.min(row.attempts(),11));
                jdbc.update("UPDATE ai_execution_close_outbox SET status='PENDING',attempts=attempts+1,next_attempt_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 second'),locked_until=NULL,last_error=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        delay,ex.getClass().getSimpleName(),row.id());
            }
        }
    }
    private Close claim(){
        var rows=jdbc.query("""
                WITH picked AS (SELECT id FROM ai_execution_close_outbox
                  WHERE (status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP) OR (status='PROCESSING' AND locked_until<=CURRENT_TIMESTAMP)
                  ORDER BY next_attempt_at,created_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                UPDATE ai_execution_close_outbox o SET status='PROCESSING',locked_until=CURRENT_TIMESTAMP+INTERVAL '120 seconds',updated_at=CURRENT_TIMESTAMP
                FROM picked p WHERE o.id=p.id
                RETURNING o.id,o.closure_id,o.agent_task_id,o.tenant_id,o.attempt_id,o.agent_id,o.operation,o.route_config_version,o.input_hash,o.expected_state_version,o.attempts
                """,(rs,n)->new Close(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getObject(5,UUID.class),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getLong(10),rs.getInt(11)));
        return rows.stream().findFirst().orElse(null);
    }
    private record Close(UUID id,UUID closureId,String taskId,UUID tenantId,UUID attemptId,String agentId,String operation,String routeVersion,String inputHash,long expectedVersion,int attempts){}
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record CloseRequest(UUID closureId,UUID tenantId,UUID attemptId,String agentId,String operation,String routeConfigVersion,String inputHash,long expectedStateVersion){}
}
