# Distributed Rate Limiting with Spring Boot, Redis & Bucket4j

A comprehensive guide to implementing production-ready, distributed rate limiting in Spring Boot applications using Redis and the Bucket4j library.

---

## 📋 Table of Contents

- [Why Rate Limiting?](#why-rate-limiting)
- [Architecture Overview](#architecture-overview)
- [How It Works](#how-it-works)
- [Project Structure](#project-structure)
- [Configuration Deep Dive](#configuration-deep-dive)
- [Getting Started](#getting-started)
- [Testing](#testing)
- [Advanced Configurations](#advanced-configurations)

---

## Why Rate Limiting?

Rate limiting is a critical technique to:
- **Prevent abuse**: Protect your API from DDoS attacks and malicious actors
- **Ensure fair usage**: Distribute resources equitably among users
- **Control costs**: Manage infrastructure expenses by limiting request volumes
- **Maintain performance**: Prevent system overload during traffic spikes

This project implements **distributed rate limiting**, meaning rate limits are shared across multiple server instances using Redis as a central store.

---

## Architecture Overview

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ HTTP Request
       ▼
┌─────────────────────────────────┐
│   Spring Boot Application       │
│  ┌──────────────────────────┐   │
│  │  RateLimitFilter         │   │  ← Intercepts all requests
│  └────────┬─────────────────┘   │
│           │                      │
│           ▼                      │
│  ┌──────────────────────────┐   │
│  │  RateLimitingService     │   │  ← Core rate limiting logic
│  └────────┬─────────────────┘   │
│           │                      │
│           ▼                      │
│  ┌──────────────────────────┐   │
│  │  Bucket4j ProxyManager   │   │  ← Manages distributed buckets
│  └────────┬─────────────────┘   │
│           │                      │
└───────────┼──────────────────────┘
            │
            ▼
     ┌─────────────┐
     │    Redis    │  ← Stores bucket state
     └─────────────┘
```

**Key Components:**
1. **Redis**: Centralized store for rate limit buckets
2. **Bucket4j**: Token bucket algorithm implementation
3. **Lettuce**: Async Redis client for high performance
4. **Servlet Filter**: Request interceptor that enforces limits

---

## How It Works

### The Token Bucket Algorithm

Rate limiting uses the **Token Bucket Algorithm**:

1. Each user gets a "bucket" with a fixed capacity of tokens (e.g., 200 tokens)
2. Each request consumes 1 token from the bucket
3. Tokens refill at a constant rate (e.g., 200 tokens per minute)
4. If the bucket is empty, the request is rejected with `429 Too Many Requests`

**Example**: With 200 requests/minute limit:
```
Initial state: [████████████████████] 200 tokens

After 5 requests: [███████████████] 195 tokens

Tokens refill over time...

Bucket empty: [] 0 tokens → Request REJECTED
```

### Request Flow

```
1. Client makes request
   ↓
2. RateLimitFilter intercepts
   ↓
3. Creates unique key: "ip:192.168.1.1"
   ↓
4. RateLimitingService.resolveBucket(key, 200)
   ↓
5. Check Redis for existing bucket or create new one
   ↓
6. bucket.tryConsumeAndReturnRemaining(1)
   ↓
7a. Token available?          7b. No tokens?
    → Allow request (200 OK)      → Reject (429 Too Many Requests)
    → Add X-Rate-Limiting-*       → Add X-Rate-Limit-Retry-After-*
```

---

## Project Structure

### Core Files

#### 1. [`RateLimitingApplication.java`](src/main/java/org/omkar/rate_limiting/RateLimitingApplication.java)
**Purpose**: Main Spring Boot application entry point.

```java
@SpringBootApplication
public class RateLimitingApplication {
    public static void main(String[] args) {
        SpringApplication.run(RateLimitingApplication.class, args);
    }
}
```

Simple bootstrapper that starts the Spring context.

---

#### 2. [`RedisConfig.java`](src/main/java/org/omkar/rate_limiting/RedisConfig.java)
**Purpose**: Configures Redis connection and Bucket4j's distributed proxy manager.

**What it does:**
- **Creates Redis client** using Lettuce (async, non-blocking)
- **Maintains persistent connection** with custom byte array codec for efficient bucket storage
- **Configures ProxyManager** to manage distributed buckets across server instances
- **Sets bucket expiration** to automatically free memory after 1 hour of inactivity

**Key Configuration:**
```java
@Bean
public ProxyManager<String> proxyManager(StatefulRedisConnection<String, byte[]> connection) {
    ClientSideConfig clientSideConfig = ClientSideConfig.getDefault()
        .withExpirationAfterWriteStrategy(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                Duration.ofHours(1) // Auto-cleanup inactive buckets
            )
        );
    
    return LettuceBasedProxyManager.builderFor(connection)
        .withClientSideConfig(clientSideConfig)
        .build();
}
```

**Why it matters**: This ensures all server instances share the same rate limit state via Redis.

---

#### 3. [`RateLimitingService.java`](src/main/java/org/omkar/rate_limiting/RateLimitingService.java)
**Purpose**: Core business logic for rate limiting. Provides multiple bucket configuration strategies.

**Available Methods:**

##### a) `resolveBucket(String key, int requestsPerMinute)`
Standard rate limiting with greedy refill.

```java
// Example: 200 requests per minute
Bucket bucket = rateLimitingService.resolveBucket("user:123", 200);
```

**Refill Strategy**: `refillGreedy` - Tokens refill proportionally over time (smooth)

---

##### b) `resolveBucketWithIntervalRefill(String key, int requestsPerMinute)`
Interval-based refill - all tokens added at once after period elapses.

```java
// Tokens refill in a batch every minute
Bucket bucket = rateLimitingService.resolveBucketWithIntervalRefil("user:456", 100);
```

**Use case**: When you want strict time windows (e.g., exactly 100 requests per minute window)

---

##### c) `resolveBucketWithBurst(String key, int requestsPerMinute, int burstCapacity)`
Allows burst traffic while maintaining average rate.

```java
// Allow 300 requests burst, but average 200/min
Bucket bucket = rateLimitingService.resolveBucketWithBurst("user:789", 200, 300);
```

**Use case**: Handle occasional traffic spikes without rejecting legitimate users

---

##### d) `resolveBucketWithInitialTokens(String key, int requestsPerMinute, int initialTokens)`
Give new users a starting quota.

```java
// Start with 50 tokens for new users
Bucket bucket = rateLimitingService.resolveBucketWithInitialTokens("newuser:999", 200, 50);
```

**Use case**: Welcome bonus for new users or testing

---

#### 4. [`RateLimitFilter.java`](src/main/java/org/omkar/rate_limiting/RateLimitFilter.java)
**Purpose**: Servlet filter that intercepts ALL HTTP requests and enforces rate limiting.

**Request Processing:**

```java
@Override
public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
    // 1. Extract client identifier (IP address)
    String clientKey = getClientKey(httpRequest);
    
    // 2. Get or create bucket for this client
    Bucket bucket = rateLimitingService.resolveBucket(clientKey, 200);
    
    // 3. Try to consume 1 token
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    
    if (probe.isConsumed()) {
        // ✅ Token available - allow request
        response.setHeader("X-Rate-Limiting-Remaining", 
            String.valueOf(probe.getRemainingTokens()));
        chain.doFilter(request, response);
    } else {
        // ❌ No tokens - reject request
        response.setStatus(429); // Too Many Requests
        response.setHeader("X-Rate-Limit-Retry-After-Seconds", 
            String.valueOf(waitForRefill));
        response.getWriter().write(
            "{\"error\":\"Rate limit exceeded. Try again in " + waitForRefill + " seconds\"}"
        );
    }
}
```

**Client Identification Strategy:**
```java
private String getClientKey(HttpServletRequest request) {
    // Check for proxy header first (e.g., behind load balancer)
    String clientIp = request.getHeader("X-Forwarded-For");
    if (clientIp == null || clientIp.isEmpty()) {
        clientIp = request.getRemoteAddr();
    }
    return "ip:" + clientIp;
}
```

**Response Headers:**
- `X-Rate-Limiting-Remaining`: How many tokens are left
- `X-Rate-Limit-Retry-After-Seconds`: When to retry (on 429 response)

---

#### 5. [`WebConfig.java`](src/main/java/org/omkar/rate_limiting/WebConfig.java)
**Purpose**: Spring MVC configuration (currently minimal).

Registers filters and configures web layer settings. Can be extended for CORS, interceptors, etc.

---

#### 6. [`ApiRateLimitInterceptor.java`](src/main/java/org/omkar/rate_limiting/ApiRateLimitInterceptor.java)
**Purpose**: Alternative approach using Spring interceptors instead of servlet filters.

**Difference from Filter:**
- Filters run at servlet container level (lower level)
- Interceptors run within Spring MVC context (can access Spring beans easily)

Use this if you need access to Spring's exception handling or want to rate limit only specific controller endpoints.

---

#### 7. [`TestController.java`](src/main/java/org/omkar/rate_limiting/TestController.java)
**Purpose**: Mock endpoints for testing rate limiting behavior.

**Endpoints:**
- `GET /api/events` - Returns sample event data
- `GET /api/test-rate-limit` - Simple test endpoint

Use these endpoints to verify rate limiting works correctly:
```bash
# Make 10 requests
for i in {1..10}; do curl http://localhost:8080/api/test-rate-limit; done

# Watch rate limit headers
curl -v http://localhost:8080/api/test-rate-limit
```

---

## Configuration Deep Dive

### Application Properties

Create `src/main/resources/application.properties`:

```properties
# Redis Configuration
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.password=  # Optional

# Application Port
server.port=8080

# Logging
logging.level.org.omkar.rate_limiting=DEBUG
```

### Customizing Rate Limits

**Per-endpoint rate limiting:**
```java
@GetMapping("/api/heavy-operation")
public ResponseEntity<?> heavyOperation(HttpServletRequest request) {
    String userId = extractUserId(request);
    Bucket bucket = rateLimitingService.resolveBucket("heavy-op:" + userId, 10); // 10/min
    
    if (!bucket.tryConsume(1)) {
        return ResponseEntity.status(429).body("Rate limit exceeded");
    }
    
    // Process request...
}
```

**Tiered rate limits:**
```java
public Bucket resolveBucketByUserTier(String userId, String tier) {
    int limit = switch(tier) {
        case "FREE" -> 100;
        case "BASIC" -> 1000;
        case "PRO" -> 10000;
        default -> 50;
    };
    return resolveBucket("user:" + userId, limit);
}
```

---

## Getting Started

### Prerequisites
- Java 21+
- Redis server running
- Maven 3.6+

### Installation

1. **Clone and build:**
   ```bash
   git clone <repo-url>
   cd rate_limiting
   mvn clean install
   ```

2. **Start Redis:**
   ```bash
   # Using Docker
   docker run -d -p 6379:6379 redis:latest
   
   # Or install locally
   redis-server
   ```

3. **Run application:**
   ```bash
   mvn spring-boot:run
   ```

4. **Test it:**
   ```bash
   # Make requests and watch headers
   curl -v http://localhost:8080/api/test-rate-limit
   
   # Keep making requests until you hit 429
   for i in {1..201}; do 
     echo "Request $i"
     curl http://localhost:8080/api/test-rate-limit
   done
   ```

---

## Testing

### Integration Tests

See [`RateLimitIntegrationTest.java`](src/test/java/org/omkar/rate_limiting/RateLimitIntegrationTest.java)

**Test isolation strategy:**
- UUID-based client identifiers prevent bucket sharing between tests
- `@DirtiesContext` reloads Spring context after each test
- `@BeforeEach` flushes Redis to ensure clean state

**Run tests:**
```bash
mvn test
```

### Manual Testing

**Monitor Redis:**
```bash
redis-cli
> KEYS *
> GET <bucket-key>
```

**Verify distributed behavior:**
1. Start application on port 8080
2. Start second instance on port 8081
3. Make requests to both - limits should be shared!

---

## Advanced Configurations

### Multiple Bandwidth Limits

Combine multiple limits (e.g., per-second AND per-minute):

```java
Bandwidth perSecond = Bandwidth.builder()
    .capacity(10)
    .refillGreedy(10, Duration.ofSeconds(1))
    .build();

Bandwidth perMinute = Bandwidth.builder()
    .capacity(200)
    .refillGreedy(200, Duration.ofMinutes(1))
    .build();

BucketConfiguration config = BucketConfiguration.builder()
    .addLimit(perSecond)
    .addLimit(perMinute)
    .build();
```

### Custom Refill Strategies

**Greedy (default)**: Smooth, proportional refill
```java
.refillGreedy(200, Duration.ofMinutes(1))
```

**Intervally**: Batch refill at fixed intervals
```java
.refillIntervally(200, Duration.ofMinutes(1))
```

### Production Considerations

1. **Monitoring**: Track 429 responses, bucket creation rate, Redis memory
2. **Security**: Use authenticated user IDs instead of IP addresses when possible
3. **Graceful degradation**: If Redis is down, decide to allow or deny requests
4. **Bucket cleanup**: Configure appropriate expiration to prevent memory leaks
5. **Rate limit headers**: Always inform clients about their limits and remaining quota

---

## Dependencies

```xml
<!-- Bucket4j Core -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>

<!-- Bucket4j Redis Integration -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-redis</artifactId>
    <version>8.10.1</version>
</dependency>

<!-- Lettuce Redis Client -->
<dependency>
    <groupId>io.lettuce</groupId>
    <artifactId>lettuce-core</artifactId>
    <!-- Version managed by Spring Boot -->
</dependency>

<!-- Spring Data Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

---

## Further Reading

- [Bucket4j Documentation](https://bucket4j.com/)
- [Token Bucket Algorithm](https://en.wikipedia.org/wiki/Token_bucket)
- [Redis Best Practices](https://redis.io/docs/manual/patterns/)
- [Spring Boot Filters vs Interceptors](https://www.baeldung.com/spring-mvc-handlerinterceptor-vs-filter)

---

## License

This project is open source and available for educational purposes.

---

## Contributing

Contributions are welcome! Feel free to submit issues or pull requests.
