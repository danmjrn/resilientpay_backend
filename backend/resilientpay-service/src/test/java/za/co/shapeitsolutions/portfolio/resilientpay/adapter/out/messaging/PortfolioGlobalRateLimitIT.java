package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.ratelimit.RedisMutationRateLimiter;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.RateLimitProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Proves one atomic, expiring global ceiling survives concurrent creation of distinct visitor tenants.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers(disabledWithoutDocker = true)
class PortfolioGlobalRateLimitIT {
    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:8.8.1-alpine").withExposedPorts(6379);

    @Test
    void concurrentDifferentTenantsCannotBypassPortfolioGlobalMutationCeiling() throws Exception {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        try {
            StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
            template.afterPropertiesSet();
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("portfolio");
            RedisMutationRateLimiter limiter = new RedisMutationRateLimiter(
                    template,
                    new RateLimitProperties(30, Duration.ofMinutes(1)),
                    new SimpleMeterRegistry(),
                    environment);
            List<Future<Boolean>> futures = new ArrayList<>();
            try (var pool = Executors.newFixedThreadPool(12)) {
                for (int index = 0; index < 130; index++) {
                    futures.add(pool.submit(() -> {
                        try {
                            limiter.requirePermit(new TenantId(UUID.randomUUID()), "accept-reversal");
                            return true;
                        } catch (ApplicationException failure) {
                            assertEquals(ApplicationErrorCode.RATE_LIMITED, failure.code());
                            return false;
                        }
                    }));
                }
                int accepted = 0;
                for (Future<Boolean> future : futures) {
                    if (future.get()) {
                        accepted++;
                    }
                }
                assertEquals(120, accepted);
            }
            String global = "resilientpay:portfolio:global-mutations:v1";
            assertEquals("130", template.opsForValue().get(global));
            Long ttl = template.getExpire(global, TimeUnit.MILLISECONDS);
            assertTrue(ttl != null && ttl > 0 && ttl <= 60000);
            RedisMutationRateLimiter legacy = new RedisMutationRateLimiter(
                    template, new RateLimitProperties(1, Duration.ofMinutes(1)), new SimpleMeterRegistry());
            TenantId tenant = new TenantId(UUID.randomUUID());
            legacy.requirePermit(tenant, "accept-reversal");
            assertThrows(ApplicationException.class, () -> legacy.requirePermit(tenant, "accept-reversal"));
            assertEquals("130", template.opsForValue().get(global));
        } finally {
            connectionFactory.destroy();
        }
    }
}
