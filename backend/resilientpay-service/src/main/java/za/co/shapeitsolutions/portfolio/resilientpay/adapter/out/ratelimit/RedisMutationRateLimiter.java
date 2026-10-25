package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ApplicationException;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.MutationAdmissionPort;
import za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config.RateLimitProperties;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Uses an atomic Redis counter as a disposable, fail-closed mutation admission boundary.
 * PostgreSQL remains authoritative for every accepted command and idempotency decision.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!test")
public final class RedisMutationRateLimiter implements MutationAdmissionPort {
    private static final DefaultRedisScript<Long> WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local admitted = 1
            for index, key in ipairs(KEYS) do
                local capacity = index == 1 and ARGV[1] or ARGV[3]
                local lifetime = index == 1 and ARGV[2] or ARGV[4]
                local current = redis.call('INCR', key)
                if current == 1 then
                    redis.call('PEXPIRE', key, lifetime)
                end
                if current > tonumber(capacity) then
                    admitted = 0
                end
            end
            return admitted
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final MeterRegistry meterRegistry;
    private final boolean portfolio;

    /**
     * Creates the Redis-backed boundary.
     *
     * @param redisTemplate string-safe Redis client
     * @param properties bounded window configuration
     * @param meterRegistry low-cardinality admission metrics
     */
    public RedisMutationRateLimiter(
            StringRedisTemplate redisTemplate, RateLimitProperties properties, MeterRegistry meterRegistry) {
        this(redisTemplate, properties, meterRegistry, false);
    }

    /** Adds one atomic shared ceiling so rotating visitor tenants cannot bypass global admission. */
    @Autowired
    public RedisMutationRateLimiter(
            StringRedisTemplate redisTemplate,
            RateLimitProperties properties,
            MeterRegistry meterRegistry,
            Environment environment) {
        this(redisTemplate, properties, meterRegistry, environment.acceptsProfiles(Profiles.of("portfolio")));
    }

    private RedisMutationRateLimiter(
            StringRedisTemplate redisTemplate,
            RateLimitProperties properties,
            MeterRegistry meterRegistry,
            boolean portfolio) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.portfolio = portfolio;
    }

    @Override
    public void requirePermit(TenantId tenantId, String action) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String safeAction = Objects.requireNonNull(action, "action must not be null");
        if (!safeAction.matches("[a-z][a-z0-9-]{2,39}")) {
            throw new IllegalArgumentException("action must be a stable lowercase code");
        }
        String key = "resilientpay:rate:v1:" + tenantId.value() + ":" + safeAction;
        try {
            Long admitted = redisTemplate.execute(
                    WINDOW_SCRIPT,
                    portfolio ? List.of(key, "resilientpay:portfolio:global-mutations:v1") : List.of(key),
                    Integer.toString(properties.capacity()),
                    Long.toString(properties.window().toMillis()),
                    "120",
                    "60000");
            if (!Long.valueOf(1).equals(admitted)) {
                meterRegistry
                        .counter("resilientpay.rate.limit", "action", safeAction, "outcome", "denied")
                        .increment();
                throw new ApplicationException(
                        ApplicationErrorCode.RATE_LIMITED, "The tenant mutation rate limit was exceeded");
            }
            meterRegistry
                    .counter("resilientpay.rate.limit", "action", safeAction, "outcome", "admitted")
                    .increment();
        } catch (DataAccessException exception) {
            meterRegistry
                    .counter("resilientpay.rate.limit", "action", safeAction, "outcome", "unavailable")
                    .increment();
            throw new ApplicationException(
                    ApplicationErrorCode.DEPENDENCY_UNAVAILABLE, "Mutation admission is temporarily unavailable");
        }
    }
}
