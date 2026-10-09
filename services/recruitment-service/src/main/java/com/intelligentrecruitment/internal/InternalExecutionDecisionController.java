package com.intelligentrecruitment.internal;
import com.intelligentrecruitment.aiplatform.infrastructure.AiExecutionLedger;
import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.time.Instant;import java.util.*;
import org.springframework.beans.factory.annotation.Value;import org.springframework.http.HttpStatus;import org.springframework.web.bind.annotation.*;import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/internal/v1/ai/execution-decisions")
public class InternalExecutionDecisionController {
 private final AiExecutionLedger ledger;private final String id,secret;
 public InternalExecutionDecisionController(AiExecutionLedger ledger,@Value("${app.boss.internal-client-id:}")String id,@Value("${app.boss.internal-client-secret:}")String secret){this.ledger=ledger;this.id=id;this.secret=secret;}
 private static boolean equal(String a,String b){return b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
 @PostMapping("/{authorizationId}")
 public Map<String,Object> close(@PathVariable UUID authorizationId,@RequestBody Deadline request,@RequestHeader("X-Internal-Client-Id")String suppliedId,@RequestHeader("X-Internal-Client-Secret")String suppliedSecret){
  if(id.isBlank()||secret.isBlank()||!equal(id,suppliedId)||!equal(secret,suppliedSecret))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"内部服务凭证无效");
  return ledger.decideDeadline(authorizationId,request.reservationId(),request.acceptedTaskId()==null?null:request.acceptedTaskId().toString(),request.reconciliationDeadline());
 }
 @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
 public record Deadline(UUID reservationId,UUID acceptedTaskId,Instant reconciliationDeadline){}
}
