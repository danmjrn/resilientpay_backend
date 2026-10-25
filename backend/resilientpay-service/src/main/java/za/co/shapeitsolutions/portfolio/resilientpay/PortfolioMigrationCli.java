package za.co.shapeitsolutions.portfolio.resilientpay;

import java.io.PrintStream;
import java.net.URI;
import java.util.Map;
import org.flywaydb.core.Flyway;

/**
 * Runs owner-only migrations before Spring boots; the long-running API receives no owner key.
 * All diagnostics are fixed safe text, never configuration, SQL exceptions or credentials.
 *
 * @author Daniel Jr Nkulu
 */
public final class PortfolioMigrationCli {
    private PortfolioMigrationCli() {}

    /** Migrates and validates only the non-demo schema using the isolated owner credentials. */
    public static int run(String[] arguments, Map<String, String> environment, PrintStream out, PrintStream err) {
        return run(arguments, environment, out, err, settings -> {
            Flyway flyway = Flyway.configure()
                    .dataSource(settings.url(), settings.username(), settings.password())
                    .locations("classpath:db/migration")
                    .validateOnMigrate(true)
                    .cleanDisabled(true)
                    .baselineOnMigrate(false)
                    .load();
            flyway.migrate();
            flyway.validate();
        });
    }

    static int run(
            String[] arguments, Map<String, String> environment, PrintStream out, PrintStream err, MigrationWork work) {
        try {
            if (arguments.length != 1
                    || !"--portfolio-migrate".equals(arguments[0])
                    || !"portfolio".equals(environment.get("SPRING_PROFILES_ACTIVE"))) {
                err.println("Portfolio migration refused: explicit portfolio profile and sole migration flag required");
                return 2;
            }
            String url = required(environment, "RESILIENTPAY_DATABASE_URL");
            String username = required(environment, "RESILIENTPAY_FLYWAY_USERNAME");
            String password = required(environment, "RESILIENTPAY_FLYWAY_PASSWORD");
            if (!url.startsWith("jdbc:postgresql://") || !username.matches("[a-z][a-z0-9_]{1,62}")) {
                throw new IllegalArgumentException("Invalid migration boundary");
            }
            URI parsed = URI.create(url.substring(5));
            if (parsed.getHost() == null
                    || parsed.getRawUserInfo() != null
                    || parsed.getRawFragment() != null
                    || parsed.getRawPath() == null
                    || !parsed.getRawPath().matches("/[a-z][a-z0-9_]{1,62}")
                    || parsed.getRawQuery() != null) {
                throw new IllegalArgumentException("Invalid migration boundary");
            }
            work.execute(new MigrationSettings(url, username, password));
            out.println("Portfolio schema migration and validation passed");
            return 0;
        } catch (RuntimeException exception) {
            err.println("Portfolio migration failed; deployment must remain inactive");
            return 1;
        }
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Missing migration input");
        }
        return value;
    }

    /**
     * Keeps owner configuration private to the short-lived migrator.
     *
     * @param url non-secret PostgreSQL network address without query credentials
     * @param username isolated owner role
     * @param password external owner credential
     * @author Daniel Jr Nkulu
     */
    record MigrationSettings(String url, String username, String password) {
        @Override
        public String toString() {
            return "MigrationSettings[credentials=REDACTED]";
        }
    }

    /**
     * Allows error-channel and configuration-fence tests without replacing production Flyway behavior.
     *
     * @author Daniel Jr Nkulu
     */
    @FunctionalInterface
    interface MigrationWork {
        void execute(MigrationSettings settings);
    }
}
