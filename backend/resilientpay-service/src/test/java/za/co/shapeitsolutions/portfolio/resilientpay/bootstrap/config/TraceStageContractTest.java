package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

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
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.core.Authentication;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.AcceptReversalRequest;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.ReversalCommandController;
import za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence.PostgresOrchestrationStore;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.AcceptReversalCommand;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.ActorContext;

/**
 * Locks the exact acceptance and persistence stage names required by release trace evidence.
 *
 * @author Daniel Jr Nkulu
 */
class TraceStageContractTest {

    @Test
    void exactObservedStageNamesAreDeclared() throws NoSuchMethodException {
        Method httpAccept = ReversalCommandController.class.getMethod(
                "accept", Authentication.class, String.class, AcceptReversalRequest.class);
        Method outboxPersist =
                PostgresOrchestrationStore.class.getMethod("accept", ActorContext.class, AcceptReversalCommand.class);

        assertObserved(httpAccept, "HTTP_ACCEPT");
        assertObserved(outboxPersist, "OUTBOX_PERSIST");
    }

    @Test
    void observedAspectIsConnectedToRegistry() {
        assertNotNull(new ObservabilityConfiguration().observedAspect(ObservationRegistry.create()));
    }

    @Test
    void annotatedServiceBoundariesCreateExactSpansThroughClassProxies() {
        CapturingSpanExporter exporter = new CapturingSpanExporter();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        try {
            OpenTelemetrySdk openTelemetry =
                    OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            Tracer tracer = new OtelTracer(
                    openTelemetry.getTracer("service-observation-test"), new OtelCurrentTraceContext(), event -> {});
            ObservationRegistry registry = ObservationRegistry.create();
            registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));

            ReversalCommandController controller = (ReversalCommandController)
                    observedProxy(new ReversalCommandController(null, null, null), registry);
            PostgresOrchestrationStore store = (PostgresOrchestrationStore) observedProxy(
                    new PostgresOrchestrationStore(null, null, null, null, null, null, null, null, null), registry);

            assertTrue(AopUtils.isCglibProxy(controller));
            assertTrue(AopUtils.isCglibProxy(store));
            assertThrows(NullPointerException.class, () -> controller.accept(null, "contract-key", null));
            assertThrows(NullPointerException.class, () -> store.accept(null, null));
            assertTrue(exporter.spanNames.containsAll(List.of("HTTP_ACCEPT", "OUTBOX_PERSIST")));
        } finally {
            provider.close();
        }
    }

    @Test
    void serviceNameDefaultsToApplicationNameButSupportsOtelOverride() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));

        assertEquals(
                "${OTEL_SERVICE_NAME:${spring.application.name}}",
                sources.getFirst().getProperty("management.opentelemetry.resource-attributes.service.name"));
        assertEquals(
                "${RESILIENTPAY_OTLP_TRACES_ENDPOINT:http:" + "/" + "/localhost:4318/v1/traces}",
                sources.getFirst().getProperty("management.opentelemetry.tracing.export.otlp.endpoint"));
        assertEquals("http", sources.getFirst().getProperty("management.opentelemetry.tracing.export.otlp.transport"));
        assertEquals(
                Boolean.TRUE,
                sources.getFirst()
                        .getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests"));
    }

    private static void assertObserved(Method method, String expectedName) {
        Observed observed = method.getAnnotation(Observed.class);
        assertNotNull(observed);
        assertEquals(expectedName, observed.name());
        assertEquals(expectedName, observed.contextualName());
    }

    private static Object observedProxy(Object target, ObservationRegistry registry) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new ObservabilityConfiguration().observedAspect(registry));
        return factory.getProxy();
    }

    /**
     * Captures completed span names synchronously for exact service-stage assertions.
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
