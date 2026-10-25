package za.co.shapeitsolutions.portfolio.resilientpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Starts the ResilientPay reversal orchestration service and its selected runtime roles.
 * This bootstrap type composes the inbound and outbound adapters around the application core.
 *
 * @author Daniel Jr Nkulu
 */
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class ResilientPayApplication {

    /**
     * Launches the Spring Boot service.
     *
     * @param arguments command-line configuration arguments
     */
    public static void main(String[] arguments) {
        if (java.util.Arrays.asList(arguments).contains("--portfolio-migrate")) {
            System.exit(PortfolioMigrationCli.run(arguments, System.getenv(), System.out, System.err));
            return;
        }
        SpringApplication.run(ResilientPayApplication.class, arguments);
    }
}
