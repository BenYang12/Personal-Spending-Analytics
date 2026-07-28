package com.ledgerlens.backend.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// @Component: component scan registers this as a bean, and Boot AUTOMATICALLY
// adds any Filter bean to the servlet filter chain — so this class starts
// guarding every endpoint the moment it exists. No registration code.
// OncePerRequestFilter guarantees one execution per request even when the
// request is forwarded internally (plain Filters can run twice).
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    // @Value injects a config property at construction. "ledgerlens.api-key"
    // is the dotted path into the YAML.
    private final String expectedKey;

    // The token bucket: 60 tokens, fully refilled every minute, "greedy" =
    // drip tokens back continuously (1 per second) rather than all at once
    // on the minute boundary — smoother than a hard window reset.
    // ONE shared bucket for now: a single API key means a single caller.
    // (Per-key buckets = a ConcurrentHashMap<String, Bucket>; unnecessary
    // until there's more than one key. Know the upgrade, don't pre-build it.)
    private final Bucket bucket = Bucket.builder()
            .addLimit(Bandwidth.builder()
                    .capacity(60)
                    .refillGreedy(60, Duration.ofMinutes(1))
                    .build())
            .build();

    public ApiKeyFilter(@Value("${ledgerlens.api-key}") String expectedKey) {
        this.expectedKey = expectedKey;
    }

    // Exempt health from the key: monitoring systems and Docker health checks
    // must reach it without credentials. Returning true SKIPS this filter.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String provided = request.getHeader("X-API-KEY");

        // MessageDigest.isEqual compares in CONSTANT TIME: it always examines
        // every byte, so response timing can't leak how much of the key was
        // right. String.equals bails at the first mismatch — a timing side
        // channel. Standard practice for comparing any secret.
        boolean authorized = provided != null && MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expectedKey.getBytes(StandardCharsets.UTF_8));

        if (!authorized) {
            // Rejection = write a status and RETURN WITHOUT calling
            // chain.doFilter. The controller never runs. Deliberately vague
            // message: don't tell an attacker whether the key was missing or
            // merely wrong.
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing X-API-KEY");
            return;
        }

        // tryConsume(1) takes a token if one is available; false = bucket dry.
        if (!bucket.tryConsume(1)) {
            reject(response, 429, "Rate limit exceeded");   // 429 Too Many Requests
            return;
        }

        // Authorized and within limit: hand off to the rest of the chain
        // (eventually reaching the controller).
        chain.doFilter(request, response);
    }

    // Filters run BELOW Spring MVC, so @RestController JSON conversion isn't
    // available here — we write the response body by hand.
    private void reject(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
