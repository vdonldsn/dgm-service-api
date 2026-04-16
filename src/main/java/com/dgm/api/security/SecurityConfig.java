package com.dgm.api.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth

                // Infrastructure - Railway health check
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                // Public endpoints - no auth required
                .requestMatchers(HttpMethod.GET,  "/api/availability/slots").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/availability/check-conflict").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/bookings/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/notifications/booking-confirmed").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/invoices/stripe-webhook").permitAll()

                // Swagger / OpenAPI
                .requestMatchers("/swagger-ui/**", "/api-docs/**", "/swagger-ui.html").permitAll()

                // Owner only
                .requestMatchers("/api/owner/**").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/invoices/send/**").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/invoices/mark-paid/**").hasRole("OWNER")
                .requestMatchers(HttpMethod.POST, "/api/invoices/create").hasAnyRole("OWNER", "TECH")

                // Owner or Tech
                .requestMatchers(HttpMethod.POST, "/api/notifications/tech-assigned").hasAnyRole("OWNER", "TECH")
                .requestMatchers(HttpMethod.POST, "/api/notifications/on-my-way").hasAnyRole("OWNER", "TECH")
                .requestMatchers(HttpMethod.POST, "/api/notifications/job-complete").hasAnyRole("OWNER", "TECH")
                .requestMatchers(HttpMethod.POST, "/api/notifications/scope-flag").hasAnyRole("OWNER", "TECH")
                .requestMatchers(HttpMethod.POST, "/api/notifications/urgent-job").hasAnyRole("OWNER", "TECH")

                // Booking — public (guest submits form, no auth)
                .requestMatchers(HttpMethod.POST, "/api/bookings").permitAll()

                // Owner work order management
                .requestMatchers("/api/owner/work-orders/**").hasRole("OWNER")

                // Tech work order actions
                .requestMatchers("/api/tech/work-orders/**").hasAnyRole("OWNER", "TECH")

                // v2 PM and contract endpoints — Owner only
                .requestMatchers("/api/pm/**").hasRole("OWNER")
                .requestMatchers("/api/contracts/**").hasRole("OWNER")

                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Allow requests from the Lovable frontend (Cloudflare Pages).
     * Update allowedOriginPatterns for each client's production domain.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of(
                "http://localhost:*",
                "https://*.pages.dev",      // Cloudflare Pages preview deployments
                "https://yourdomain.com"    // TODO: replace with client production domain
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
