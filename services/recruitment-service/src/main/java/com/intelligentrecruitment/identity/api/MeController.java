package com.intelligentrecruitment.identity.api;

import com.intelligentrecruitment.identity.application.IdentityService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MeController {
    private final IdentityService identityService;

    public MeController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @GetMapping("/me")
    IdentityService.UserView me(Authentication authentication) {
        return identityService.me(CurrentUser.id(authentication));
    }

    @PutMapping("/me/display-name")
    IdentityService.UserView updateDisplayName(@Valid @RequestBody DisplayNameRequest request,
                                               Authentication authentication) {
        identityService.updateDisplayName(CurrentUser.id(authentication), request.displayName());
        return identityService.me(CurrentUser.id(authentication));
    }

    public record DisplayNameRequest(String displayName) {}
}
