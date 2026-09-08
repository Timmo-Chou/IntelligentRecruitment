package com.intelligentrecruitment.shared.security;

import com.intelligentrecruitment.boss.application.BossControlPlaneClient;
import com.intelligentrecruitment.boss.application.BossRequestContext;
import com.intelligentrecruitment.shared.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private final BossControlPlaneClient boss;

    public BearerTokenFilter(BossControlPlaneClient boss) {
        this.boss = boss;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String accessToken = header.substring(7);
            try {
                var user = boss.currentUser(accessToken);
                if ("ACTIVE".equals(user.status())) {
                    AuthenticatedUser principal = new AuthenticatedUser(user.userId(), accessToken);
                    BossRequestContext.set(user.userId(), accessToken);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
                }
            } catch (ApiException exception) {
                if (exception.status() == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
                        || exception.status() == org.springframework.http.HttpStatus.BAD_GATEWAY) {
                    response.setStatus(exception.status().value());
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"" + exception.code() + "\",\"message\":\"BOSS 服务暂不可用，请稍后重试\"}");
                    return;
                }
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            BossRequestContext.clear();
        }
    }
}
