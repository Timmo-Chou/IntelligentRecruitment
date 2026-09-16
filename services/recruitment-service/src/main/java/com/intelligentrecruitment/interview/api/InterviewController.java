package com.intelligentrecruitment.interview.api;
import com.intelligentrecruitment.interview.application.InterviewService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import com.intelligentrecruitment.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1/tenants/{tenantId}/interview-kits")
public class InterviewController {private final InterviewService service;public InterviewController(InterviewService service){this.service=service;}
 @GetMapping List<InterviewService.KitSummary> list(@PathVariable UUID tenantId,Authentication a){return service.list(CurrentUser.id(a),tenantId);}
 @PostMapping InterviewService.KitDetail create(@PathVariable UUID tenantId,@RequestBody InterviewService.CreateInput i,Authentication a){throw new ApiException("INTERVIEW_KIT_RECRUITMENT_TASK_REQUIRED","请通过招聘任务发起面试题生成，以执行 BOSS 授权预占、模型任务和结算。",HttpStatus.CONFLICT);}
 @GetMapping("/{kitId}") InterviewService.KitDetail get(@PathVariable UUID tenantId,@PathVariable UUID kitId,Authentication a){return service.get(CurrentUser.id(a),tenantId,kitId);}
 @PutMapping("/{kitId}") InterviewService.KitDetail update(@PathVariable UUID tenantId,@PathVariable UUID kitId,@RequestBody List<InterviewService.QuestionInput> q,Authentication a){return service.update(CurrentUser.id(a),tenantId,kitId,q);}
 @PostMapping("/{kitId}/confirm") InterviewService.KitDetail confirm(@PathVariable UUID tenantId,@PathVariable UUID kitId,Authentication a){return service.confirm(CurrentUser.id(a),tenantId,kitId);}}
