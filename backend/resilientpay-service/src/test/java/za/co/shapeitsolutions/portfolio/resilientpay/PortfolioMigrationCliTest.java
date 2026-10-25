package za.co.shapeitsolutions.portfolio.resilientpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves the early migration mode's profile/credential fences and sanitized failure channels.
 *
 * @author Daniel Jr Nkulu
 */
class PortfolioMigrationCliTest {
    @Test
    void acceptsOnlyPortfolioAndTheSoleExplicitMigrationFlag() {
        AtomicInteger calls = new AtomicInteger();
        for (String profile : new String[] {"demo", "test", "portfolio,demo", "production", ""}) {
            Map<String, String> environment = environment();
            environment.put("SPRING_PROFILES_ACTIVE", profile);
            assertEquals(
                    2, execute(new String[] {"--portfolio-migrate"}, environment, settings -> calls.incrementAndGet()));
        }
        assertEquals(
                2,
                execute(
                        new String[] {"--portfolio-migrate", "--spring.profiles.active=demo"},
                        environment(),
                        settings -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
    }

    @Test
    void migrationRequiresNoRuntimeDatabasePasswordOrMessagingSessionEvidenceSecrets() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(0, execute(new String[] {"--portfolio-migrate"}, environment(), settings -> {
            calls.incrementAndGet();
            assertEquals("resilientpay_owner", settings.username());
            assertEquals("jdbc:postgresql://postgres:5432/resilientpay_production", settings.url());
            assertFalse(settings.toString().contains(settings.password()));
        }));
        assertEquals(1, calls.get());
    }

    @Test
    void missingOwnerAndCredentialBearingUrlsFailBeforeAnyMigrationCall() {
        AtomicInteger calls = new AtomicInteger();
        for (String key : new String[] {
            "RESILIENTPAY_DATABASE_URL", "RESILIENTPAY_FLYWAY_USERNAME", "RESILIENTPAY_FLYWAY_PASSWORD"
        }) {
            Map<String, String> environment = environment();
            environment.remove(key);
            assertEquals(
                    1, execute(new String[] {"--portfolio-migrate"}, environment, settings -> calls.incrementAndGet()));
        }
        for (String url : new String[] {
            "jdbc:postgresql://user:private@postgres/app",
            "jdbc:postgresql://postgres/app?password=private",
            "jdbc:postgresql://postgres/app#private",
            "jdbc:h2:mem:test"
        }) {
            Map<String, String> environment = environment();
            environment.put("RESILIENTPAY_DATABASE_URL", url);
            assertEquals(
                    1, execute(new String[] {"--portfolio-migrate"}, environment, settings -> calls.incrementAndGet()));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void failureNeverPrintsRawExceptionConfigurationOrCredential() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String privateValue = environment().get("RESILIENTPAY_FLYWAY_PASSWORD");
        int status = PortfolioMigrationCli.run(
                new String[] {"--portfolio-migrate"},
                environment(),
                new PrintStream(output),
                new PrintStream(error),
                settings -> {
                    throw new IllegalStateException(privateValue + " SQL-private-sentinel");
                });
        assertEquals(1, status);
        String diagnostics = output.toString(StandardCharsets.UTF_8) + error.toString(StandardCharsets.UTF_8);
        assertTrue(diagnostics.contains("deployment must remain inactive"));
        assertFalse(diagnostics.contains(privateValue));
        assertFalse(diagnostics.contains("SQL-private-sentinel"));
        assertFalse(diagnostics.contains("IllegalStateException"));
    }

    private static int execute(
            String[] arguments, Map<String, String> environment, PortfolioMigrationCli.MigrationWork work) {
        return PortfolioMigrationCli.run(
                arguments,
                environment,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                work);
    }

    private static Map<String, String> environment() {
        return new HashMap<>(Map.of(
                "SPRING_PROFILES_ACTIVE",
                "portfolio",
                "RESILIENTPAY_DATABASE_URL",
                "jdbc:postgresql://postgres:5432/resilientpay_production",
                "RESILIENTPAY_FLYWAY_USERNAME",
                "resilientpay_owner",
                "RESILIENTPAY_FLYWAY_PASSWORD",
                "synthetic-migrator-test-only"));
    }
}
