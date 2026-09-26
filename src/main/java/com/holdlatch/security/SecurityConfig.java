package com.holdlatch.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.config.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, SecurityProblemHandlers problems, RateLimitFilter rateLimitFilter) throws Exception {
        http
                // Stateless bearer-token API: there is no cookie session for a forged cross-site request to ride on.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // No login: the caller is Stripe, proven by the request signature checked in the controller.
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/stripe").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Checked here, before any request body is parsed, so a non-organizer always gets 403 and never a validation error.
                        .requestMatchers(HttpMethod.POST, "/api/events", "/api/events/*/publish").hasRole("ORGANIZER")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .addFilterBefore(rateLimitFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    RateLimitFilter rateLimitFilter(SecurityProperties props, ObjectMapper objectMapper) {
        return new RateLimitFilter(props.rateLimit(), objectMapper);
    }

    @Bean
    JwtDecoder jwtDecoder(JwtService jwtService) {
        return jwtService.decoder();
    }

    @Bean
    PasswordEncoder passwordEncoder(SecurityProperties props) {
        return new BCryptPasswordEncoder(props.bcryptStrength());
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
