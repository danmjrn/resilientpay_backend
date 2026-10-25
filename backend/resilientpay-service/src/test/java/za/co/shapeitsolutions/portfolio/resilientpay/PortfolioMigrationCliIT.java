package za.co.shapeitsolutions.portfolio.resilientpay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Executes real owner-only Flyway migration with no Spring context or runtime service secrets.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers(disabledWithoutDocker = true)
class PortfolioMigrationCliIT {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    private static Map<String, String> environment;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void separateOwnerAndRuntimeRoles() {
        JdbcTemplate bootstrap = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        String ownerPassword = UUID.randomUUID().toString();
        bootstrap.execute("CREATE ROLE resilientpay_owner LOGIN PASSWORD '" + ownerPassword
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute(
                "CREATE ROLE resilientpay_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        bootstrap.execute("ALTER DATABASE test OWNER TO resilientpay_owner");
        bootstrap.execute("ALTER SCHEMA public OWNER TO resilientpay_owner");
        bootstrap.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        environment = Map.of(
                "SPRING_PROFILES_ACTIVE",
                "portfolio",
                "RESILIENTPAY_DATABASE_URL",
                POSTGRES.getJdbcUrl().split("\\?", 2)[0],
                "RESILIENTPAY_FLYWAY_USERNAME",
                "resilientpay_owner",
                "RESILIENTPAY_FLYWAY_PASSWORD",
                ownerPassword);
        jdbc = new JdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "resilientpay_owner", ownerPassword));
    }

    @Test
    void freshAndRepeatedMigrationValidateWithNoDemoSeedsOrElevatedRuntimeRole() {
        for (int attempt = 0; attempt < 2; attempt++) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream error = new ByteArrayOutputStream();
            assertEquals(
                    0,
                    PortfolioMigrationCli.run(
                            new String[] {"--portfolio-migrate"},
                            environment,
                            new PrintStream(output),
                            new PrintStream(error)));
            assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .contains("migration and validation passed"));
            assertEquals("", error.toString(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(
                6,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success",
                        Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM original_transaction", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operation_policy", Integer.class));
        assertFalse(jdbc.queryForObject(
                "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
                Boolean.class));
        assertTrue(jdbc.queryForObject(
                "SELECT has_table_privilege('resilientpay_runtime', 'original_transaction', 'INSERT')", Boolean.class));
        assertFalse(jdbc.queryForObject(
                "SELECT has_schema_privilege('resilientpay_runtime', 'public', 'CREATE')", Boolean.class));
        assertFalse(jdbc.queryForObject(
                "SELECT has_table_privilege('resilientpay_runtime', 'audit_event', 'DELETE')", Boolean.class));
    }
}
