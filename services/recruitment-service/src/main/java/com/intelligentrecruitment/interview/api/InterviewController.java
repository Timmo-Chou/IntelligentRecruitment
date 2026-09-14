package com.intelligentrecruitment.interview.api;
import com.intelligentrecruitment.interview.application.InterviewService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1/companies/{companyId}/interview-kits")
public class InterviewController {private final InterviewService service;public InterviewController(InterviewService service){this.service=service;}
 @GetMapping List<InterviewService.KitSummary> list(@PathVariable UUID companyId,Authentication a){return service.list(CurrentUser.id(a),companyId);}
 @PostMapping InterviewService.KitDetail create(@PathVariable UUID companyId,@RequestBody InterviewService.CreateInput i,Authentication a){return service.create(CurrentUser.id(a),companyId,i);}
 @GetMapping("/{kitId}") InterviewService.KitDetail get(@PathVariable UUID companyId,@PathVariable UUID kitId,Authentication a){return service.get(CurrentUser.id(a),companyId,kitId);}
 @PutMapping("/{kitId}") InterviewService.KitDetail update(@PathVariable UUID companyId,@PathVariable UUID kitId,@RequestBody List<InterviewService.QuestionInput> q,Authentication a){return service.update(CurrentUser.id(a),companyId,kitId,q);}
 @PostMapping("/{kitId}/confirm") InterviewService.KitDetail confirm(@PathVariable UUID companyId,@PathVariable UUID kitId,Authentication a){return service.confirm(CurrentUser.id(a),companyId,kitId);}}
