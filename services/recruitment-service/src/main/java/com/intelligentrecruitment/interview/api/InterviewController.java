package com.intelligentrecruitment.interview.api;
import com.intelligentrecruitment.interview.application.InterviewService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1/workspaces/{workspaceId}/interview-kits")
public class InterviewController {private final InterviewService service;public InterviewController(InterviewService service){this.service=service;}
 @GetMapping List<InterviewService.KitSummary> list(@PathVariable UUID workspaceId,Authentication a){return service.list(CurrentUser.id(a),workspaceId);}
 @PostMapping InterviewService.KitDetail create(@PathVariable UUID workspaceId,@RequestBody InterviewService.CreateInput i,Authentication a){return service.create(CurrentUser.id(a),workspaceId,i);}
 @GetMapping("/{kitId}") InterviewService.KitDetail get(@PathVariable UUID workspaceId,@PathVariable UUID kitId,Authentication a){return service.get(CurrentUser.id(a),workspaceId,kitId);}
 @PutMapping("/{kitId}") InterviewService.KitDetail update(@PathVariable UUID workspaceId,@PathVariable UUID kitId,@RequestBody List<InterviewService.QuestionInput> q,Authentication a){return service.update(CurrentUser.id(a),workspaceId,kitId,q);}
 @PostMapping("/{kitId}/confirm") InterviewService.KitDetail confirm(@PathVariable UUID workspaceId,@PathVariable UUID kitId,Authentication a){return service.confirm(CurrentUser.id(a),workspaceId,kitId);}}
