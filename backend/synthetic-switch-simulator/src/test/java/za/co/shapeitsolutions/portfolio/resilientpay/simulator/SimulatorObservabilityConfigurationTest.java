package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies that listener and publisher observations are enabled and exported over configured OTLP.
 *
 * @author Daniel Jr Nkulu
 */
class SimulatorObservabilityConfigurationTest {

    @Test
    void amqpObservationsAndOtlpExportAreEnabled() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("simulator", new ClassPathResource("application.yml"));
        PropertySource<?> configuration = sources.getFirst();

        assertEquals(Boolean.TRUE, configuration.getProperty("spring.rabbitmq.template.observation-enabled"));
        assertEquals(Boolean.TRUE, configuration.getProperty("spring.rabbitmq.listener.simple.observation-enabled"));
        assertEquals(
                "${RESILIENTPAY_OTLP_TRACES_ENDPOINT:http:" + "/" + "/localhost:4318/v1/traces}",
                configuration.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"));
        assertEquals("http", configuration.getProperty("management.opentelemetry.tracing.export.otlp.transport"));
        assertEquals(
                "${RESILIENTPAY_OTLP_METRICS_ENDPOINT:http:" + "/" + "/localhost:4318/v1/metrics}",
                configuration.getProperty("management.otlp.metrics.export.url"));
        assertNotNull(configuration.getProperty("management.tracing.sampling.probability"));
        assertEquals(
                "${OTEL_SERVICE_NAME:${spring.application.name}}",
                configuration.getProperty("management.opentelemetry.resource-attributes.service.name"));
    }

    @Test
    void switchExecutionBoundaryCreatesExactSpanThroughClassProxy() throws Exception {
        Method consume = SyntheticSwitchListener.class.getMethod("consume", Message.class);
        Observed observed = consume.getAnnotation(Observed.class);
        assertNotNull(observed);
        assertEquals("SWITCH_EXECUTION", observed.name());
        assertEquals("SWITCH_EXECUTION", observed.contextualName());

        CapturingSpanExporter exporter = new CapturingSpanExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try {
            OpenTelemetrySdk openTelemetry =
                    OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            Tracer tracer = new OtelTracer(
                    openTelemetry.getTracer("simulator-observation-test"), new OtelCurrentTraceContext(), event -> {});
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
            SyntheticSwitchListener target = new SyntheticSwitchListener(null, null, null, null, null, null);
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAspect(new SimulatorObservabilityConfiguration().observedAspect(registry));
            SyntheticSwitchListener proxy = factory.getProxy();

            assertTrue(AopUtils.isCglibProxy(proxy));
            assertThrows(NullPointerException.class, () -> proxy.consume(new Message(new byte[0])));
            assertTrue(exporter.spanNames.contains("SWITCH_EXECUTION"));
        } finally {
            provider.close();
        }
    }

    /**
     * Captures completed span names synchronously for the exact observation contract.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class CapturingSpanExporter implements SpanExporter {
        private final List<String> spanNames = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            spans.forEach(span -> spanNames.add(span.getName()));
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
