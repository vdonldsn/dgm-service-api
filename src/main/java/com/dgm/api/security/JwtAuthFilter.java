package com.dgm.api.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Validates Supabase JWTs (HS256) on every request.
 * Extracts user id (sub) and role from app_metadata.role claim.
 * Sets anonymous auth if token is missing — endpoint security
 * decides whether anonymous access is allowed.
 */
@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Value("${supabase.jwt-secret}")
    private String jwtSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);

        try {
            DecodedJWT jwt = JWT.require(Algorithm.HMAC256(jwtSecret))
                    .build()
                    .verify(token);

            String userId = jwt.getSubject();
            String role = extractRole(jwt);

            var auth = new UsernamePasswordAuthenticationToken(
                    userId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
            );
            SecurityContextHolder.getContext().setAuthentication(auth);

        } catch (JWTVerificationException ex) {
            log.warn("Invalid JWT: {}", ex.getMessage());
            // Don't set auth — let Spring Security deny if endpoint requires it
        }

        chain.doFilter(request, response);
    }

    /**
     * Supabase stores custom roles in app_metadata.role.
     * Falls back to "customer" if not present.
     */
    private String extractRole(DecodedJWT jwt) {
        try {
            Map<String, Object> appMeta = jwt.getClaim("app_metadata").asMap();
            if (appMeta != null && appMeta.containsKey("role")) {
                return appMeta.get("role").toString();
            }
        } catch (Exception e) {
            log.debug("No app_metadata.role in JWT, defaulting to customer");
        }
        return "customer";
    }
}
