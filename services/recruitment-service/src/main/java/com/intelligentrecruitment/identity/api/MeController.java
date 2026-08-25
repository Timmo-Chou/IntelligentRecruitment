package com.intelligentrecruitment.identity.api;

import com.intelligentrecruitment.identity.application.IdentityService;
import com.intelligentrecruitment.shared.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
