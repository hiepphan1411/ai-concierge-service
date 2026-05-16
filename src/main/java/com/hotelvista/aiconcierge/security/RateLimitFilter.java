package com.hotelvista.aiconcierge.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${rate.limit.ai.requests-per-minute:20}")
    private int requestsPerMinute;

    private final Map<String, Bucket> userBuckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String uri = request.getRequestURI();
        String method = request.getMethod();

        if (isRateLimitedEndpoint(uri, method)) {
            String userId = extractUserId(request);

            if (userId != null) {
                Bucket bucket = userBuckets.computeIfAbsent(userId, this::createBucket);

                if (!bucket.tryConsume(1)) {
                    log.warn("Rate limit exceeded for userId: {} on URI: {}", userId, uri);
                    response.setStatus(429);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"error\":\"Too many requests. Please wait a moment before sending another message.\","
                            + "\"retryAfterSeconds\":60}"
                    );
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }


    private Bucket createBucket(String userId) {
        Bandwidth limit = Bandwidth.classic(
                requestsPerMinute,
                Refill.greedy(requestsPerMinute, Duration.ofMinutes(1))
        );
        return Bucket.builder().addLimit(limit).build();
    }

    private String extractUserId(HttpServletRequest request) {

        String headerUserId = request.getHeader("X-User-Id");
        if (headerUserId != null && !headerUserId.isBlank()) {
            return headerUserId;
        }

        String sessionId = request.getSession(false) != null
                ? request.getSession().getId() : null;
        return sessionId != null ? sessionId : request.getRemoteAddr();
    }

    /** Chỉ rate-limit POST /api/ai/chat */
    private boolean isRateLimitedEndpoint(String uri, String method) {
        return "POST".equalsIgnoreCase(method) && uri.contains("/ai/chat")
                && !uri.contains("/ai/chat/new");
    }
}
