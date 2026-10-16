package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Enables explicit low-cardinality method observations for release trace-stage evidence.
 *
 * @author Daniel Jr Nkulu
 */
@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class ObservabilityConfiguration {

    /** Connects {@code @Observed} boundaries to the application observation registry. */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}
