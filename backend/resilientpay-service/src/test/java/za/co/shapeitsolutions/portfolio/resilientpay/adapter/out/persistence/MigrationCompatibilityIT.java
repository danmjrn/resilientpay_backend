package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves every supported historical database state migrates to V6 and that V3 writers fail closed.
 *
 * @author Daniel Jr Nkulu
 */
@Testcontainers(disabledWithoutDocker = true)
class MigrationCompatibilityIT {
    private static final String MIGRATION_LOCATION = "classpath:db/migration";
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ORIGINAL_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID POLICY_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID COMMAND_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final Instant FIXTURE_TIME = Instant.parse("2026-08-25T12:34:56.123456Z");
    private static final String IDEMPOTENCY_HASH = "1".repeat(64);
    private static final String REQUEST_HASH = "2".repeat(64);
    private static final String ACTION_FINGERPRINT = "3".repeat(64);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");

    @Test
    void emptyDatabaseMigratesToV6() {
        assertMigratesToV6(0);
    }

    @Test
    void v1DatabaseMigratesToV6() {
        assertMigratesToV6(1);
    }

    @Test
    void v2DatabaseMigratesToV6() {
        assertMigratesToV6(2);
    }

    @Test
    void v3DatabaseMigratesToV6() {
        assertMigratesToV6(3);
    }

    @Test
    void v4DatabaseMigratesToV6() {
        assertMigratesToV6(4);
    }

    @Test
    void v5DatabaseMigratesToV6() {
        assertMigratesToV6(5);
    }

    private void assertMigratesToV6(int startingVersion) {
        String schema = "migration_from_v" + startingVersion;
        JdbcTemplate jdbc = databaseInFreshSchema(schema);
        if (startingVersion > 0) {
            flyway(schema, startingVersion).migrate();
            seedHistoricalRows(jdbc, startingVersion);
        }
        String v4Row = startingVersion == 4 ? commandJson(jdbc) : null;

        Flyway current = flyway(schema, null);
        current.migrate();
        current.validate();

        assertEquals(List.of("1", "2", "3", "4", "5", "6"), successfulVersions(jdbc));
        assertEquals(0, current.migrate().migrationsExecuted);
        assertTrue(hasTimelineIndex(jdbc));
        assertEquals(startingVersion == 0 ? 0 : 1, commandCount(jdbc));
        if (startingVersion > 0) {
            assertBackfilledCommand(jdbc);
        }
        if (startingVersion == 4) {
            assertEquals(v4Row, commandJson(jdbc), "V6 must not mutate V4 command data");
        }
    }

    @Test
    void v3ShapedWriterFailsClosedAgainstV6WithoutChangingRowCount() {
        String schema = "migration_v3_writer_boundary";
        JdbcTemplate jdbc = databaseInFreshSchema(schema);
        Flyway current = flyway(schema, null);
        current.migrate();
        current.validate();
        seedReferenceRows(jdbc);
        int before = commandCount(jdbc);

        DataIntegrityViolationException failure = assertThrows(
                DataIntegrityViolationException.class,
                () -> insertCommand(jdbc, 3),
                "a V3-shaped insert must not bypass V4 policy-snapshot requirements");
        SQLException databaseFailure = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());

        assertEquals("23502", databaseFailure.getSQLState());
        assertTrue(
                databaseFailure.getMessage().contains("_snapshot"),
                "the fail-closed boundary must be a missing V4 policy snapshot");
        assertEquals(before, commandCount(jdbc));
        assertTrue(hasTimelineIndex(jdbc));
    }

    private JdbcTemplate databaseInFreshSchema(String schema) {
        DriverManagerDataSource administration =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate admin = new JdbcTemplate(administration);
        admin.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        admin.execute("CREATE SCHEMA " + schema);
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        DriverManagerDataSource isolated = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
        return new JdbcTemplate(isolated);
    }

    private Flyway flyway(String schema, Integer target) {
        DriverManagerDataSource dataSource = (DriverManagerDataSource) databaseInExistingSchema(schema);
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations(MIGRATION_LOCATION);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target.toString()));
        }
        return configuration.load();
    }

    private javax.sql.DataSource databaseInExistingSchema(String schema) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private void seedHistoricalRows(JdbcTemplate jdbc, int version) {
        seedReferenceRows(jdbc);
        insertCommand(jdbc, version);
    }

    private void seedReferenceRows(JdbcTemplate jdbc) {
        Timestamp timestamp = Timestamp.from(FIXTURE_TIME);
        jdbc.update("""
                INSERT INTO original_transaction (
                    id, tenant_id, external_reference_token, operation_type, amount, currency,
                    status, occurred_at, created_at, updated_at, version
                ) VALUES (?, ?, ?, 'CARD_PURCHASE', 10.0000, 'ZAR', 'POSTED', ?, ?, ?, 0)
                """, ORIGINAL_ID, TENANT_ID, "a".repeat(64), timestamp, timestamp, timestamp);
        jdbc.update("""
                INSERT INTO operation_policy (
                    id, tenant_id, operation_type, policy_version, status, max_attempts,
                    initial_retry_delay_ms, maximum_retry_delay_ms, result_timeout_ms,
                    effective_from, created_by, submitted_by, approved_by,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'CARD_PURCHASE', 1, 'ACTIVE', 3, 100, 1000, 30000,
                          ?, 'fixture-author', 'fixture-maker', 'fixture-checker', ?, ?, 0)
                """, POLICY_ID, TENANT_ID, timestamp, timestamp, timestamp);
    }

    private void insertCommand(JdbcTemplate jdbc, int writerVersion) {
        Timestamp timestamp = Timestamp.from(FIXTURE_TIME);
        if (writerVersion < 3) {
            jdbc.update(
                    """
                    INSERT INTO reversal_command (
                        id, tenant_id, original_transaction_id, operation_policy_id,
                        idempotency_key_hash, request_hash, correlation_id, operation_type,
                        amount, currency, reason_code, state, accepted_at, created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'CARD_PURCHASE', 10.0000, 'ZAR',
                              'CUSTOMER_REQUEST', 'ACCEPTED', ?, ?, ?, 0)
                    """,
                    COMMAND_ID,
                    TENANT_ID,
                    ORIGINAL_ID,
                    POLICY_ID,
                    IDEMPOTENCY_HASH,
                    REQUEST_HASH,
                    CORRELATION_ID,
                    timestamp,
                    timestamp,
                    timestamp);
            return;
        }
        if (writerVersion == 3) {
            jdbc.update(
                    """
                    INSERT INTO reversal_command (
                        id, tenant_id, original_transaction_id, operation_policy_id,
                        idempotency_key_hash, request_hash, correlation_id, operation_type,
                        amount, currency, reason_code, state, accepted_at, created_at, updated_at, version,
                        demonstration_failure, action_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'CARD_PURCHASE', 10.0000, 'ZAR',
                              'CUSTOMER_REQUEST', 'ACCEPTED', ?, ?, ?, 0, 'NONE', ?)
                    """,
                    COMMAND_ID,
                    TENANT_ID,
                    ORIGINAL_ID,
                    POLICY_ID,
                    IDEMPOTENCY_HASH,
                    REQUEST_HASH,
                    CORRELATION_ID,
                    timestamp,
                    timestamp,
                    timestamp,
                    ACTION_FINGERPRINT);
            return;
        }
        jdbc.update(
                """
                INSERT INTO reversal_command (
                    id, tenant_id, original_transaction_id, operation_policy_id,
                    idempotency_key_hash, request_hash, correlation_id, operation_type,
                    amount, currency, reason_code, state, accepted_at, created_at, updated_at, version,
                    demonstration_failure, action_fingerprint, policy_version_snapshot,
                    max_attempts_snapshot, result_timeout_ms_snapshot,
                    initial_retry_delay_ms_snapshot, maximum_retry_delay_ms_snapshot
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'CARD_PURCHASE', 10.0000, 'ZAR',
                          'CUSTOMER_REQUEST', 'ACCEPTED', ?, ?, ?, 0, 'NONE', ?, 1, 3, 30000, 100, 1000)
                """,
                COMMAND_ID,
                TENANT_ID,
                ORIGINAL_ID,
                POLICY_ID,
                IDEMPOTENCY_HASH,
                REQUEST_HASH,
                CORRELATION_ID,
                timestamp,
                timestamp,
                timestamp,
                ACTION_FINGERPRINT);
    }

    private List<String> successfulVersions(JdbcTemplate jdbc) {
        return jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
    }

    private boolean hasTimelineIndex(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_indexes
                    WHERE schemaname = current_schema()
                      AND tablename = 'reversal_command'
                      AND indexname = 'ix_reversal_command_tenant_timeline'
                      AND indexdef LIKE '%(tenant_id, accepted_at DESC, id DESC)%'
                )
                """, Boolean.class));
    }

    private int commandCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM reversal_command", Integer.class);
    }

    private String commandJson(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT row_to_json(command)::text FROM reversal_command command WHERE id = ?",
                String.class,
                COMMAND_ID);
    }

    private void assertBackfilledCommand(JdbcTemplate jdbc) {
        assertNotNull(jdbc.queryForObject(
                "SELECT action_fingerprint FROM reversal_command WHERE id = ?", String.class, COMMAND_ID));
        assertEquals(
                List.of(1, 3, 30000, 100, 1000),
                jdbc.queryForObject(
                        """
                        SELECT ARRAY[
                            policy_version_snapshot,
                            max_attempts_snapshot::integer,
                            result_timeout_ms_snapshot::integer,
                            initial_retry_delay_ms_snapshot::integer,
                            maximum_retry_delay_ms_snapshot::integer
                        ] FROM reversal_command WHERE id = ?
                        """,
                        (result, row) -> List.of((Integer[]) result.getArray(1).getArray()),
                        COMMAND_ID));
    }
}
