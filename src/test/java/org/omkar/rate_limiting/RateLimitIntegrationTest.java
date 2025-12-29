package org.omkar.rate_limiting;

import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StatefulRedisConnection<String, byte[]> redisConnection;

    @BeforeEach
    void setUp() {
        // Clear Redis before each test to ensure clean state
        redisConnection.sync().flushall();
    }

    @Test
    void shouldAllowRequestsWithinLimit() throws Exception {
        // Use unique client identifier for this test to avoid bucket sharing
        String uniqueClientId = UUID.randomUUID().toString();
        
        // Should succeed for first 10 requests
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/events")
                            .header("X-Forwarded-For", uniqueClientId))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void shouldRejectRequestsExceedingLimit() throws Exception {
        // Use unique client identifier for this test to avoid bucket sharing
        String uniqueClientId = UUID.randomUUID().toString();
        String endpoint = "/api/test-rate-limit";

        // Make requests up to the limit (200 requests per minute)
        // First 200 requests should succeed
        for (int i = 0; i < 200; i++) {
            mockMvc.perform(get(endpoint)
                    .header("X-Forwarded-For", uniqueClientId))
                    .andExpect(status().isOk());
        }

        // 201st request should be rate limited (all 200 tokens consumed)
        mockMvc.perform(get(endpoint)
                        .header("X-Forwarded-For", uniqueClientId))
                .andExpect(status().isTooManyRequests());
    }
}
