package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Locks the Flyway schema features required by the PostgreSQL runtime adapters.
 *
 * @author Daniel Jr Nkulu
 */
class RuntimeMigrationContractTest {

    @Test
    void migrationProvidesTenantIdempotencyInboxOutboxAndAuditBoundaries() throws IOException {
        String core = resource("db/migration/V1__core_reversal_schema.sql");
        String reliability = resource("db/migration/V2__reliable_messaging_and_audit.sql");
        String integrity = resource("db/migration/V3__runtime_adapter_integrity.sql");
        String recovery = resource("db/migration/V4__bounded_outbox_cold_recovery.sql");
        String queryPerformance = resource("db/migration/V5__bounded_reversal_timeline_query.sql");

        assertTrue(core.contains("tenant_id UUID NOT NULL"));
        assertTrue(core.contains("external_reference_token CHAR(64) NOT NULL"));
        assertTrue(core.contains("max_attempts BETWEEN 1 AND 5"));
        assertTrue(reliability.contains("PRIMARY KEY (tenant_id, action, key_hash)"));
        assertTrue(reliability.contains("CREATE TABLE outbox_event"));
        assertTrue(reliability.contains("CREATE TABLE inbox_message"));
        assertTrue(reliability.contains("CREATE TABLE audit_event"));
        assertTrue(integrity.contains("SHA256(CONVERT_TO"));
        assertTrue(integrity.contains("ALTER COLUMN reason_code TYPE VARCHAR(120)"));
        assertTrue(integrity.contains("domain_purpose_code VARCHAR(64)"));
        assertTrue(integrity.contains("public_reference_token VARCHAR(80)"));
        assertTrue(recovery.contains("retryable BOOLEAN NOT NULL DEFAULT TRUE"));
        assertTrue(recovery.contains("recovery_cycle_count INTEGER NOT NULL DEFAULT 0"));
        assertTrue(recovery.contains("recovery_cycle_count BETWEEN 0 AND 10"));
        assertTrue(recovery.contains("WHERE status = 'DEAD' AND retryable"));
        assertTrue(recovery.contains("policy_version_snapshot INTEGER"));
        assertTrue(recovery.contains("max_attempts_snapshot SMALLINT"));
        assertTrue(recovery.contains("result_timeout_ms_snapshot BIGINT"));
        assertTrue(queryPerformance.contains("ON reversal_command (tenant_id, accepted_at DESC, id DESC)"));
    }

    @Test
    void repeatableDemoSeedContainsExactlyFourPublicSyntheticFixtures() throws IOException {
        String seed = resource("db/devseed/R__synthetic_demo_seed.sql");

        assertEquals(4, occurrences(seed, "synth-txn-"));
        assertEquals(4, occurrences(seed, "'ACTIVE'"));
        assertTrue(seed.contains("synth-txn-card-0001"));
        assertTrue(seed.contains("synth-txn-transfer-001"));
        assertTrue(seed.contains("synth-txn-cash-0001"));
        assertTrue(seed.contains("synth-txn-merchant-01"));
        assertTrue(seed.contains("ON CONFLICT DO NOTHING"));
    }

    private static int occurrences(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }

    private static String resource(String location) throws IOException {
        return new ClassPathResource(location).getContentAsString(StandardCharsets.UTF_8);
    }
}
