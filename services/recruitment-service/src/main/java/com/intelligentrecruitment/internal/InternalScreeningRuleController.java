package com.intelligentrecruitment.internal;

import com.intelligentrecruitment.screening.application.ScreeningService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/platform/screening-rules/tenants/{tenantId}")
public class InternalScreeningRuleController {
    private final ScreeningService screening;
    private final String clientId,clientSecret;
    public InternalScreeningRuleController(ScreeningService screening,@Value("${app.boss.internal-client-id:}") String clientId,
            @Value("${app.boss.internal-client-secret:}") String clientSecret){this.screening=screening;this.clientId=clientId;this.clientSecret=clientSecret;}
    private void authenticate(String id,String secret){
        if(clientId.isBlank()||clientSecret.isBlank()||!equal(clientId,id)||!equal(clientSecret,secret))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"内部服务凭证无效");
    }
    private static boolean equal(String expected,String actual){return actual!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));}
    @GetMapping
    public List<ScreeningService.ScreeningPlanView> list(@PathVariable UUID tenantId,@RequestParam(required=false) UUID recruitmentTaskId,
            @RequestHeader("X-Internal-Client-Id") String id,@RequestHeader("X-Internal-Client-Secret") String secret){authenticate(id,secret);return screening.listPlansByAdmin(tenantId,recruitmentTaskId);}
    @PostMapping
    public ScreeningService.ScreeningPlanView create(@PathVariable UUID tenantId,@RequestBody ScreeningService.PlanInput input,
            @RequestHeader("X-Platform-Admin-Id") UUID admin,@RequestHeader("X-Internal-Client-Id") String id,@RequestHeader("X-Internal-Client-Secret") String secret){authenticate(id,secret);return screening.createPlanByAdmin(admin,tenantId,input);}
    @PostMapping("/{planId}")
    public ScreeningService.ScreeningPlanView update(@PathVariable UUID tenantId,@PathVariable UUID planId,@RequestBody ScreeningService.PlanUpdateInput input,
            @RequestHeader("X-Platform-Admin-Id") UUID admin,@RequestHeader("X-Internal-Client-Id") String id,@RequestHeader("X-Internal-Client-Secret") String secret){authenticate(id,secret);return screening.updatePlanByAdmin(admin,tenantId,planId,input);}
}
