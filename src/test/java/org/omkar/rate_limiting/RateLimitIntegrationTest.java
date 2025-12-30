package org.omkar.rate_limiting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RateLimitIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldAllowRequestsWithinLimit() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/events"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void shouldRejectRequestsExceedingLimit() throws Exception {
        String endpoint = "/api/analytics";

        for (int i = 0; i < 50; i++) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get(endpoint))
                .andExpect(status().isTooManyRequests());
    }
}
