package za.co.shapeitsolutions.portfolio.resilientpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.DemoTokenController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security.PortfolioRequestSecurity;

/**
 * Boots the actual portfolio application with owner-only migration and a credential-restricted runtime.
 * The real HTTP, PostgreSQL, RabbitMQ and Redis adapters run in an isolated synthetic test environment.
 *
 * @author Daniel Jr Nkulu
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.address=127.0.0.1",
            "resilientpay.runtime.roles=api",
            "management.tracing.enabled=false",
            "management.otlp.metrics.export.enabled=false"
        })
@ActiveProfiles("portfolio")
@Testcontainers(disabledWithoutDocker = true)
class PortfolioApplicationIT {
    private static final String ORIGIN = "https://portfolio.example.test";
    private static final String ACCESS_KEY = PortfolioTestKeys.sequentialHex(0);
    private static final String JWT_KEY = PortfolioTestKeys.sequentialHex(64);
    private static final String RUNTIME_PASSWORD = UUID.randomUUID().toString();
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.2.9-management-alpine");

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.1-alpine")
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext application;

    @DynamicPropertySource
    static void runtimeOnlyConfiguration(DynamicPropertyRegistry properties) {
        JdbcTemplate bootstrap = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        String ownerPassword = UUID.randomUUID().toString();
        bootstrap.execute("CREATE ROLE resilientpay_owner LOGIN PASSWORD '" + ownerPassword
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("CREATE ROLE resilientpay_runtime LOGIN PASSWORD '" + RUNTIME_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("ALTER DATABASE test OWNER TO resilientpay_owner");
        bootstrap.execute("ALTER SCHEMA public OWNER TO resilientpay_owner");
        bootstrap.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        Map<String, String> migrator = Map.of(
                "SPRING_PROFILES_ACTIVE",
                "portfolio",
                "RESILIENTPAY_DATABASE_URL",
                POSTGRES.getJdbcUrl().split("\\?", 2)[0],
                "RESILIENTPAY_FLYWAY_USERNAME",
                "resilientpay_owner",
                "RESILIENTPAY_FLYWAY_PASSWORD",
                ownerPassword);
        int migrated = PortfolioMigrationCli.run(
                new String[] {"--portfolio-migrate"},
                migrator,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        if (migrated != 0) {
            throw new IllegalStateException("Isolated portfolio migration failed");
        }
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "resilientpay_runtime");
        properties.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        properties.add("spring.flyway.enabled", () -> false);
        properties.add("spring.rabbitmq.host", RABBIT::getHost);
        properties.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        properties.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        properties.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        properties.add("resilientpay.security.evidence-hmac-key", () -> "synthetic-independent-portfolio-evidence");
        properties.add("resilientpay.messaging.hmac-key", () -> "synthetic-independent-portfolio-messaging");
        properties.add("resilientpay.http.allowed-origins[0]", () -> ORIGIN);
        properties.add("resilientpay.portfolio.origin", () -> ORIGIN);
        properties.add("resilientpay.portfolio.access-cookie-key", () -> ACCESS_KEY);
        properties.add("resilientpay.portfolio.jwt-key", () -> JWT_KEY);
    }

    @Test
    @DirtiesContext
    void realPortfolioProfileRunsWithOnlyRuntimeCredentialsAndIsolatedVisitorApi() throws Exception {
        assertFalse(application.getEnvironment().containsProperty("RESILIENTPAY_FLYWAY_PASSWORD"));
        assertEquals("false", application.getEnvironment().getProperty("spring.flyway.enabled"));
        assertTrue(application.getBeansOfType(DemoTokenController.class).isEmpty());
        Browser first = browser();
        Browser second = browser();
        assertEquals(4, originalCount(first));
        HttpResponse<String> created = exchange(
                "POST",
                "/api/v1/originaltransactions",
                first,
                "{\"transactionToken\":\"synth-http-portfolio-fresh\",\"operationType\":\"CARD_PURCHASE\",\"amount\":31.01,\"currency\":\"ZAR\"}");
        assertEquals(201, created.statusCode());
        assertEquals(5, originalCount(first));
        assertEquals(4, originalCount(second));
        assertEquals(404, exchange("POST", "/api/v1/demo/token", first, "{}").statusCode());
        assertEquals(
                204,
                exchange("POST", "/api/v1/portfolio/session/logout", first, null)
                        .statusCode());
        assertEquals(
                401,
                exchange("GET", "/api/v1/originaltransactions", first, null).statusCode());
        assertEquals(
                200,
                exchange("GET", "/api/v1/originaltransactions", second, null).statusCode());
    }

    private int originalCount(Browser browser) throws Exception {
        HttpResponse<String> response = exchange("GET", "/api/v1/originaltransactions", browser, null);
        assertEquals(200, response.statusCode());
        var payload = JSON.readTree(response.body());
        assertTrue(payload.isArray());
        return payload.size();
    }

    private Browser browser() throws Exception {
        String outer = gate();
        HttpResponse<String> created = exchange("POST", "/api/v1/portfolio/session", new Browser(outer, null), null);
        assertEquals(201, created.statusCode());
        String cookies = outer + "; "
                + created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        HttpResponse<String> persona = exchange(
                "POST",
                "/api/v1/portfolio/session/persona",
                new Browser(cookies, null),
                "{\"role\":\"REVERSAL_OPERATOR\"}");
        assertEquals(200, persona.statusCode());
        return new Browser(
                cookies, JSON.readTree(persona.body()).get("accessToken").asText());
    }

    private HttpResponse<String> exchange(String method, String path, Browser browser, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Cookie", browser.cookies())
                .header("Origin", ORIGIN)
                .header("Accept", "application/json");
        if (browser.token() != null) {
            request.header("Authorization", "Bearer " + browser.token());
        }
        if ("POST".equals(method)) {
            request.header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json");
        }
        return HttpClient.newHttpClient()
                .send(
                        request.method(
                                        method,
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private static String gate() throws Exception {
        long issued = Instant.now().getEpochSecond();
        String payload = "v1|session|" + issued + "|" + (issued + 28800) + "|"
                + UUID.randomUUID().toString().replace("-", "");
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String encoded = encoder.encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(ACCESS_KEY), "HmacSHA256"));
        return PortfolioRequestSecurity.ACCESS_COOKIE + "=" + encoded + "."
                + encoder.encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * Holds only ephemeral test credentials and never renders them into diagnostics.
     *
     * @param cookies browser credentials
     * @param token short-lived persona bearer
     * @author Daniel Jr Nkulu
     */
    private record Browser(String cookies, String token) {
        @Override
        public String toString() {
            return "Browser[credentials=REDACTED]";
        }
    }
}
