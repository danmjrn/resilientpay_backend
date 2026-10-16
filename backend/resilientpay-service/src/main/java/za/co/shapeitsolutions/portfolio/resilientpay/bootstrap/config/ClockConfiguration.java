package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides deterministic boundaries for time and retry jitter to application and adapter code.
 *
 * @author Daniel Jr Nkulu
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    /**
     * Provides the production UTC clock.
     *
     * @return UTC system clock
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Provides a process-local secure jitter source; tests replace it with a deterministic generator.
     *
     * @return retry jitter source
     */
    @Bean
    RandomGenerator retryRandomGenerator() {
        return new SecureRandom();
    }
}
