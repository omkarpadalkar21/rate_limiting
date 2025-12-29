package org.omkar.rate_limiting;

import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
// Endpoint specific rate limiting
public class ApiRateLimitInterceptor implements HandlerInterceptor {
    private final RateLimitingService rateLimitingService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String endpoint = request.getRequestURI();
        String clientKey = request.getRemoteAddr() + ":" + endpoint;

        // Different limits for different endpoints
        int rateLimit = getEndpointRateLimit(endpoint);
        Bucket bucket = rateLimitingService.resolveBucket(clientKey, rateLimit);

        if (bucket.tryConsume(1)) {
            return true;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        return false;
    }

    private int getEndpointRateLimit(String endpoint) {
        if (endpoint.startsWith("/api/events")) return 100;
        if (endpoint.startsWith("/api/analytics")) return 50;
        return 200; // Default
    }
}
