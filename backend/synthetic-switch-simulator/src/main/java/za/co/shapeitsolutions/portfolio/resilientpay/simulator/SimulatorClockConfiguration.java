package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides an injectable UTC clock so duplicate and interruption behavior is testable.
 *
 * @author Daniel Jr Nkulu
 */
@Configuration(proxyBeanMethods = false)
public class SimulatorClockConfiguration {

    /** Returns the production UTC clock. */
    @Bean
    Clock simulatorClock() {
        return Clock.systemUTC();
    }
}
