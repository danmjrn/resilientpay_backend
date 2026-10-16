package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the isolated synthetic downstream switch used by the local recovery demonstration.
 *
 * @author Daniel Jr Nkulu
 */
@ConfigurationPropertiesScan
@SpringBootApplication
public class SyntheticSwitchApplication {

    /** Launches the simulator process. */
    public static void main(String[] arguments) {
        SpringApplication.run(SyntheticSwitchApplication.class, arguments);
    }
}
