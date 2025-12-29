package org.omkar.rate_limiting;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;


@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter implements Filter {

    private final RateLimitingService rateLimitingService;


    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
        HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;

        // Create unique key per client (use IP address or user ID)
        String clientKey = getClientKey(httpRequest);

        // Get or create bucket for this client
        Bucket bucket = rateLimitingService.resolveBucket(clientKey, 200); // 200 reqs per minute

        // Try to consume 1 token
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // Request allowed - add rate limit headers
            httpResponse.setHeader("X-Rate-Limiting-Remaining", String.valueOf(probe.getRemainingTokens()));
            filterChain.doFilter(servletRequest, servletResponse);
        } else {
            // Rate limit exceeded
            long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_100; // converting to seconds
            httpResponse.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            httpResponse.setHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(waitForRefill));
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write(
                    String.format("{\"error\":\"Rate limit exceeded. Try again in %d seconds\"}", waitForRefill)
            );

            log.warn("Rate limit exceeded for client: {}", clientKey);
        }
    }

    private String getClientKey(HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = httpRequest.getRemoteAddr();
        }

        // Option 2: Use user ID if authenticated (more accurate)
        // String userId = extractUserIdFromToken(request);
        // return "user:" + (userId != null ? userId : clientIp);

        return "ip" + clientIp;
    }
}
