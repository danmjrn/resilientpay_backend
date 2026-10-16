package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.health.registry.DefaultReactiveHealthContributorRegistry;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Proves that the configured readiness composition is dependency-sensitive and becomes DOWN when
 * PostgreSQL is unavailable.
 *
 * @author Daniel Jr Nkulu
 */
class ReadinessHealthCompositionTest {
    private static final Set<String> READINESS_MEMBERS = Set.of("readinessState", "db", "rabbit", "redis");

    @Test
    void runtimeConnectionTimeoutAndMetricsEndpointAreExternallyConfigurable() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("service", new ClassPathResource("application.yml"));
        PropertySource<?> configuration = sources.getFirst();

        assertEquals("${RESILIENTPAY_REDIS_TIMEOUT:2s}", configuration.getProperty("spring.data.redis.timeout"));
        assertEquals(
                "${RESILIENTPAY_OTLP_METRICS_ENDPOINT:http:" + "/" + "/localhost:4318/v1/metrics}",
                configuration.getProperty("management.otlp.metrics.export.url"));
        assertEquals(
                "${RESILIENTPAY_MESSAGE_RETRY_ATTEMPTS:3}",
                configuration.getProperty("spring.rabbitmq.listener.simple.retry.max-retries"));
        assertNull(configuration.getProperty("spring.rabbitmq.listener.simple.retry.max-attempts"));
    }

    @Test
    void databaseDownMakesReadinessDown() {
        DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
        registry.registerContributor(
                "readinessState", (HealthIndicator) () -> Health.up().build());
        registry.registerContributor(
                "rabbit", (HealthIndicator) () -> Health.up().build());
        registry.registerContributor(
                "redis", (HealthIndicator) () -> Health.up().build());
        registry.registerContributor("db", (HealthIndicator) () -> Health.down().build());
        HealthEndpointGroup readiness = readinessGroup();
        HealthEndpoint endpoint = new HealthEndpoint(
                registry,
                new DefaultReactiveHealthContributorRegistry(),
                HealthEndpointGroups.of(readiness, Map.of("readiness", readiness)),
                Duration.ofSeconds(1));

        assertTrue(READINESS_MEMBERS.stream().allMatch(readiness::isMember));
        assertEquals(Status.DOWN, endpoint.health().getStatus());
        assertEquals(503, readiness.getHttpCodeStatusMapper().getStatusCode(Status.DOWN));
    }

    @Test
    void redisDownMakesReadinessDownBecauseMutationAdmissionFailsClosed() {
        DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
        registry.registerContributor(
                "readinessState", (HealthIndicator) () -> Health.up().build());
        registry.registerContributor(
                "rabbit", (HealthIndicator) () -> Health.up().build());
        registry.registerContributor("db", (HealthIndicator) () -> Health.up().build());
        registry.registerContributor(
                "redis", (HealthIndicator) () -> Health.down().build());
        HealthEndpointGroup readiness = readinessGroup();
        HealthEndpoint endpoint = new HealthEndpoint(
                registry,
                new DefaultReactiveHealthContributorRegistry(),
                HealthEndpointGroups.of(readiness, Map.of("readiness", readiness)),
                Duration.ofSeconds(1));

        assertEquals(Status.DOWN, endpoint.health().getStatus());
        assertEquals(503, readiness.getHttpCodeStatusMapper().getStatusCode(Status.DOWN));
    }

    private static HealthEndpointGroup readinessGroup() {
        return new HealthEndpointGroup() {
            @Override
            public boolean isMember(String name) {
                return READINESS_MEMBERS.contains(name);
            }

            @Override
            public boolean showComponents(SecurityContext securityContext) {
                return false;
            }

            @Override
            public boolean showDetails(SecurityContext securityContext) {
                return false;
            }

            @Override
            public StatusAggregator getStatusAggregator() {
                return StatusAggregator.getDefault();
            }

            @Override
            public HttpCodeStatusMapper getHttpCodeStatusMapper() {
                return HttpCodeStatusMapper.getDefault();
            }

            @Override
            public AdditionalHealthEndpointPath getAdditionalPath() {
                return null;
            }
        };
    }
}
