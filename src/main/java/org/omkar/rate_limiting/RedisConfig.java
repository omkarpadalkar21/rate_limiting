package org.omkar.rate_limiting;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ClientSideConfig;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RedisConfig {
    @Value("${spring.data.redis.host}")
    private String redisHost;
    @Value("${spring.data.redis.port}")
    private int redisPort;

    // Creates connection to Redis server
    @Bean
    public RedisClient redisClient() {
        RedisURI.Builder builder = RedisURI.Builder
                .redis(redisHost, redisPort);

        return RedisClient.create(builder.build());
    }

    // Maintains persistent connection with custom codec for storing bucket data as bytes
    @Bean
    public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient redisClient) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return redisClient.connect(codec);
    }

    // Manages distributed buckets across server instances and handles bucket expiration to free memory
    @Bean
    public ProxyManager<String> proxyManager(StatefulRedisConnection<String, byte[]> connection) {
        ClientSideConfig clientSideConfig = ClientSideConfig.getDefault()
                .withExpirationAfterWriteStrategy(
                        ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                Duration.ofHours(1) // Buckets expire after 1 hour of inactivity
                        )
                );

        return LettuceBasedProxyManager.builderFor(connection)
                .withClientSideConfig(clientSideConfig)
                .build();
    }
}
