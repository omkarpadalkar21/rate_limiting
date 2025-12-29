package org.omkar.rate_limiting;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitingService {
    private final ProxyManager<String> proxyManager;

    /*
     * Creates or retrieves a bucket for the given key.
     * The bucket is stored in Redis and shared across all server instances.
     */
    public Bucket resolveBucket(String key, int requestsPerMinute) {
        Supplier<BucketConfiguration> configurationSupplier = () -> createBucketConfiguration(requestsPerMinute);
        return proxyManager.builder().build(key, configurationSupplier);
    }

    /*
     * Creates bucket configuration with specified rate limit.
     * Refill.intervally: Refills the entire capacity at fixed intervals
     * Bandwidth.classic: Defines the capacity and refill strategy
     */
    private BucketConfiguration createBucketConfiguration(int tokensPerMinute) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(tokensPerMinute)
                .refillGreedy(tokensPerMinute, Duration.ofMinutes(1))
                .build();

        return BucketConfiguration.builder()
                .addLimit(limit)
                .build();
    }

    /*
     * Creates bucket with interval-based refill.
     * All tokens are added at once after the period elapses.
     */
    public Bucket resolveBucketWithIntervalRefil(String key, int requestsPerMinute) {
        Supplier<BucketConfiguration> configurationSupplier = () -> {
            Bandwidth limit = Bandwidth.builder()
                    .capacity(requestsPerMinute)
                    // Specifies how many tokens and how often the bucket refills
                    .refillIntervally(requestsPerMinute, Duration.ofMinutes(1))
                    .build();

            return BucketConfiguration.builder()
                    .addLimit(limit)
                    .build();
        };

        // Doesn't always create new buckets; returns existing ones from Redis if present
        return proxyManager.builder().build(key, configurationSupplier);
    }

    /*
     * Creates a bucket with burst capacity using multiple bandwidths.
     * Useful for allowing occasional spikes while maintaining average rate.
     */
    public Bucket resolveBucketWithBurst(String key, int requestsPerMinute, int burstCapacity) {
        Supplier<BucketConfiguration> configurationSupplier = () -> {
            Bandwidth steadyRate = Bandwidth.builder()
                    .capacity(requestsPerMinute)
                    .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                    .build();

            Bandwidth burstRate = Bandwidth.builder()
                    .capacity(requestsPerMinute)
                    .refillGreedy(burstCapacity, Duration.ofMinutes(1))
                    .build();

            return BucketConfiguration.builder()
                    .addLimit(steadyRate)
                    .addLimit(burstRate)
                    .build();
        };

        return proxyManager.builder().build(key, configurationSupplier);
    }

    /*
     * Creates bucket with initial tokens (useful for giving new users a starting quota).
     */
    public Bucket resolveBucketWithInitialTokens(String key, int requestsPerMinute, int initialTokens) {
        Supplier<BucketConfiguration> configurationSupplier = () -> {
            Bandwidth limit = Bandwidth.builder()
                    .capacity(requestsPerMinute)
                    .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                    .initialTokens(initialTokens)
                    .build();

            return BucketConfiguration.builder()
                    .addLimit(limit)
                    .build();
        };

        return proxyManager.builder().build(key, configurationSupplier);
    }
}
