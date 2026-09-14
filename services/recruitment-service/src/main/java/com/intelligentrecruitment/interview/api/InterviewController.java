package com.intelligentrecruitment.interview.api;
import com.intelligentrecruitment.interview.application.InterviewService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1/tenants/{tenantId}/interview-kits")
public class InterviewController {private final InterviewService service;public InterviewController(InterviewService service){this.service=service;}
 @GetMapping List<InterviewService.KitSummary> list(@PathVariable UUID tenantId,Authentication a){return service.list(CurrentUser.id(a),tenantId);}
 @PostMapping InterviewService.KitDetail create(@PathVariable UUID tenantId,@RequestBody InterviewService.CreateInput i,Authentication a){return service.create(CurrentUser.id(a),tenantId,i);}
 @GetMapping("/{kitId}") InterviewService.KitDetail get(@PathVariable UUID tenantId,@PathVariable UUID kitId,Authentication a){return service.get(CurrentUser.id(a),tenantId,kitId);}
 @PutMapping("/{kitId}") InterviewService.KitDetail update(@PathVariable UUID tenantId,@PathVariable UUID kitId,@RequestBody List<InterviewService.QuestionInput> q,Authentication a){return service.update(CurrentUser.id(a),tenantId,kitId,q);}
 @PostMapping("/{kitId}/confirm") InterviewService.KitDetail confirm(@PathVariable UUID tenantId,@PathVariable UUID kitId,Authentication a){return service.confirm(CurrentUser.id(a),tenantId,kitId);}}
