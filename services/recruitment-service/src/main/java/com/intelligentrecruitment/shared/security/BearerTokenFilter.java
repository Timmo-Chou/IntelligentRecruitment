package com.intelligentrecruitment.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.intelligentrecruitment.shared.database.SqlTimes.timestamp;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {

    private final JdbcTemplate jdbcTemplate;

    public BearerTokenFilter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String tokenHash = SecurityHashes.sha256(header.substring(7));
            List<UUID> users = jdbcTemplate.query("""
                    SELECT user_id FROM access_tokens
                    WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
                    """, (rs, rowNum) -> rs.getObject("user_id", UUID.class), tokenHash, timestamp(Instant.now()));
            if (!users.isEmpty()) {
                AuthenticatedUser principal = new AuthenticatedUser(users.getFirst());
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, tokenHash, List.of()));
            }
        }
        chain.doFilter(request, response);
    }
}
