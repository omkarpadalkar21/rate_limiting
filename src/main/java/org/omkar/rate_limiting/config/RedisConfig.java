package org.omkar.rate_limiting.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis configuration class for distributed rate limiting using bucket4j and Redisson.
 * This configuration sets up the necessary beans to store rate limit buckets in Redis,
 * allowing rate limiting to work across multiple application instances.
 */
@Configuration
public class RedisConfig {
    /**
     * Creates and configures the Redisson configuration object.
     * Sets up a single-server Redis connection to localhost on the default port 6379.
     *
     * @return Redisson Config object with single-server configuration
     */
    @Bean
    public Config config() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:6379");
        return config;
    }

    /**
     * Creates the RedissonClient bean using the provided configuration.
     * RedissonClient is the main interface for interacting with Redis through Redisson.
     *
     * @param config the Redisson configuration object
     * @return RedissonClient instance connected to Redis
     */
    @Bean
    public RedissonClient redissonClient(Config config) {
        return Redisson.create(config);
    }

    /**
     * Creates the ProxyManager for bucket4j distributed rate limiting.
     * This ProxyManager uses Redisson as the backend to store rate limit buckets in Redis.
     * The String type parameter represents the key type used to identify different rate limit buckets.
     *
     * @param redissonClient the RedissonClient instance
     * @return ProxyManager that manages distributed rate limit buckets
     */
    @Bean
    ProxyManager<String> proxyManager(RedissonClient redissonClient) {
        return RedissonBasedProxyManager.builderFor(((Redisson) redissonClient).getCommandExecutor())
                .build();
    }
}
