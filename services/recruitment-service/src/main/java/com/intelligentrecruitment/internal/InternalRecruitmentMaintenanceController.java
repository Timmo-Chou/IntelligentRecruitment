package com.intelligentrecruitment.internal;

import com.intelligentrecruitment.agentflow.application.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/recruitment/maintenance")
public class InternalRecruitmentMaintenanceController {
 private final RecruitmentExecutionMaintenance gate;private final RecruitmentReleaseValidationService validation;private final String token;
 public InternalRecruitmentMaintenanceController(RecruitmentExecutionMaintenance gate,RecruitmentReleaseValidationService validation,@Value("${app.release.control-token:}")String token){this.gate=gate;this.validation=validation;this.token=token;}
 private void authenticate(String header){if(token.isBlank()||header==null||!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),header.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"发布控制凭证无效");}
 @PostMapping
 public Map<String,Object> change(@RequestHeader("Authorization")String authorization,@RequestBody Change request){authenticate(authorization);return gate.change(request.state(),request.cutoverId(),request.routeConfigVersion(),request.protocolUpgrade(),request.versionMatrix());}
 @PostMapping("/validation-runs")
 public Map<String,UUID> validate(@RequestHeader("Authorization")String authorization,@RequestBody Validation request){authenticate(authorization);return Map.of("id",validation.create(request.cutoverId(),request.tenantId(),request.actorId(),request.input()));}
 @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
 public record Change(String state,UUID cutoverId,String routeConfigVersion,boolean protocolUpgrade,Map<String,String> versionMatrix){}
 @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
 public record Validation(UUID cutoverId,UUID tenantId,UUID actorId,Map<String,Object> input){}
}
