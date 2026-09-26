package com.holdlatch.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.holdlatch.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client-IP token-bucket limiter, with a much stricter bucket for the
 * login/register endpoints (password guessing) than for the rest of the API.
 * Buckets live in a size-bounded, expiring cache so a flood of distinct IPs
 * cannot grow memory without limit. Limits are per application instance.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/api/auth/";

    private final SecurityProperties.RateLimit limits;
    private final ObjectMapper objectMapper;
    private final Cache<String, TokenBucket> apiBuckets = newBucketCache();
    private final Cache<String, TokenBucket> authBuckets = newBucketCache();

    public RateLimitFilter(SecurityProperties.RateLimit limits, ObjectMapper objectMapper) {
        this.limits = limits;
        this.objectMapper = objectMapper;
    }

    private static Cache<String, TokenBucket> newBucketCache() {
        return Caffeine.newBuilder().maximumSize(200_000).expireAfterAccess(Duration.ofMinutes(10)).build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // getRemoteAddr on purpose: X-Forwarded-For is client-controlled and would let an attacker pick their own bucket.
        String client = request.getRemoteAddr();
        boolean authEndpoint = request.getRequestURI().startsWith(AUTH_PATH_PREFIX);

        TokenBucket bucket = authEndpoint
                ? authBuckets.get(client, k -> new TokenBucket(limits.authCapacity(), limits.authRefillPerSecond(), System::nanoTime))
                : apiBuckets.get(client, k -> new TokenBucket(limits.apiCapacity(), limits.apiRefillPerSecond(), System::nanoTime));

        if (bucket.tryConsume()) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfter = bucket.secondsUntilNextToken();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, slow down.");
        problem.setTitle("Rate limit exceeded");
        problem.setProperty("code", "RATE_LIMITED");
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
