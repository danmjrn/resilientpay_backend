package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies explicit W3C correlation and bounded trace-header handling without a broker.
 *
 * @author Daniel Jr Nkulu
 */
class RabbitTraceContextTest {
    private static final UUID PUBLIC_CORRELATION_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private SdkTracerProvider provider;
    private Tracer tracer;
    private RabbitTraceContext traceContext;
    private CapturingSpanExporter exporter;
    private Logger bridgeLogger;
    private Level previousLogLevel;
    private boolean previousAdditivity;
    private ListAppender<ILoggingEvent> capturedLogs;

    @BeforeEach
    void configureTracing() {
        exporter = new CapturingSpanExporter();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        ContextPropagators propagators = ContextPropagators.create(W3CTraceContextPropagator.getInstance());
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(propagators)
                .build();
        OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
        tracer = new OtelTracer(openTelemetry.getTracer("resilientpay-test"), currentTraceContext, event -> {});
        traceContext = new RabbitTraceContext(
                tracer, new OtelPropagator(propagators, openTelemetry.getTracer("resilientpay-test")));
        bridgeLogger = (Logger) LoggerFactory.getLogger(RabbitTraceContext.class);
        previousLogLevel = bridgeLogger.getLevel();
        previousAdditivity = bridgeLogger.isAdditive();
        bridgeLogger.setLevel(Level.INFO);
        bridgeLogger.setAdditive(false);
        capturedLogs = new ListAppender<>();
        capturedLogs.setContext(bridgeLogger.getLoggerContext());
        capturedLogs.start();
        bridgeLogger.addAppender(capturedLogs);
    }

    @AfterEach
    void closeTracing() {
        bridgeLogger.detachAppender(capturedLogs);
        capturedLogs.stop();
        bridgeLogger.setLevel(previousLogLevel);
        bridgeLogger.setAdditive(previousAdditivity);
        provider.close();
    }

    @Test
    void producerAndConsumerShareTraceButUseDistinctSpansAndNoBaggage() {
        MessageProperties outbound = new MessageProperties();
        Span request = tracer.nextSpan().name("http accept").start();
        String requestTraceId = request.context().traceId();
        OutboxTraceContext durableContext;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            durableContext = traceContext.captureCurrent();
        } finally {
            request.end();
        }

        String producerSpanId;
        try (RabbitTraceContext.TraceScope producer =
                traceContext.startProducer(outbound, "ReversalExecutionRequested.v1", durableContext)) {
            producerSpanId = tracer.currentSpan().context().spanId();
        }

        String traceparent = outbound.getHeader("traceparent");
        assertTrue(traceContext.isValid(outbound));
        assertEquals(requestTraceId, traceparent.substring(3, 35));
        assertNull(outbound.getHeader("baggage"));

        MessageProperties inbound = new MessageProperties();
        inbound.setHeader("traceparent", traceparent);
        try (RabbitTraceContext.TraceScope consumer =
                traceContext.startConsumer(inbound, "ReversalOutcomeObserved.v1")) {
            assertEquals(requestTraceId, tracer.currentSpan().context().traceId());
            assertNotEquals(producerSpanId, tracer.currentSpan().context().spanId());
        }
        assertTrue(exporter.spanNames.contains("OUTBOX_PUBLISH"));
        assertTrue(exporter.spanNames.contains("OUTCOME_CONSUME"));
    }

    @Test
    void rejectsZeroIdsUppercaseOversizedStateAndStateWithoutParent() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("traceparent", "00-00000000000000000000000000000000-0123456789abcdef-01");
        assertFalse(traceContext.isValid(properties));

        properties.setHeader("traceparent", "00-0123456789ABCDEF0123456789ABCDEF-0123456789abcdef-01");
        assertFalse(traceContext.isValid(properties));

        properties.setHeader("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
        properties.setHeader("tracestate", "vendor=" + "x".repeat(513));
        assertFalse(traceContext.isValid(properties));

        properties.getHeaders().remove("traceparent");
        properties.setHeader("tracestate", "vendor=value");
        assertFalse(traceContext.isValid(properties));
    }

    @Test
    void validatedPublicCorrelationProducesOnlySafeStructuredBridgeFieldsWithoutTraceTags() {
        String traceId;
        try (RabbitTraceContext.TraceScope ignored =
                traceContext.startConsumer(new MessageProperties(), "ReversalOutcomeObserved.v1")) {
            traceId = tracer.currentSpan().context().traceId();
            traceContext.recordValidatedCorrelation(PUBLIC_CORRELATION_ID);
        }

        assertEquals(1, capturedLogs.list.size());
        ILoggingEvent event = capturedLogs.list.getFirst();
        assertEquals("PUBLIC_CORRELATION_TRACE_LINK", event.getFormattedMessage());
        assertEquals(
                Map.of("correlationId", PUBLIC_CORRELATION_ID.toString(), "w3cTraceId", traceId),
                event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value)));
        assertNull(event.getThrowableProxy());
        JsonNode document = ecsJson(event);
        assertEquals("PUBLIC_CORRELATION_TRACE_LINK", document.get("message").asText());
        assertEquals(
                PUBLIC_CORRELATION_ID.toString(), document.get("correlationId").asText());
        assertEquals(traceId, document.get("w3cTraceId").asText());
        assertFalse(document.has("tenantId"));
        assertFalse(document.has("commandId"));
        assertFalse(document.has("messageId"));
        assertFalse(document.has("payload"));
        assertFalse(exporter.spans.getFirst().getAttributes().toString().contains(PUBLIC_CORRELATION_ID.toString()));
    }

    @Test
    void missingPublicCorrelationOrActiveSpanProducesNoBridge() {
        traceContext.recordValidatedCorrelation(PUBLIC_CORRELATION_ID);
        try (RabbitTraceContext.TraceScope ignored =
                traceContext.startConsumer(new MessageProperties(), "ReversalOutcomeObserved.v1")) {
            traceContext.recordValidatedCorrelation(null);
        }
        assertTrue(capturedLogs.list.isEmpty());
    }

    @Test
    void noopSpanProducesNoBridge() {
        Tracer noopTracer = mock(Tracer.class);
        when(noopTracer.currentSpan()).thenReturn(Span.NOOP);

        new RabbitTraceContext(noopTracer, Propagator.NOOP).recordValidatedCorrelation(PUBLIC_CORRELATION_ID);

        assertTrue(capturedLogs.list.isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "00000000000000000000000000000000",
                "0123456789ABCDEF0123456789ABCDEF",
                "0123456789abcdef",
                "0123456789abcdef0123456789abcdef00",
                "0123456789abcdef0123456789abcdef\nFORGED",
                "Bearer PRIVATE-CANARY"
            })
    void unsafeTraceIdentifiersProduceNoBridge(String suppliedTraceId) {
        Tracer invalidTracer = mock(Tracer.class);
        Span invalidSpan = mock(Span.class);
        TraceContext invalidContext = mock(TraceContext.class);
        when(invalidTracer.currentSpan()).thenReturn(invalidSpan);
        when(invalidSpan.context()).thenReturn(invalidContext);
        when(invalidContext.traceId()).thenReturn(suppliedTraceId);

        new RabbitTraceContext(invalidTracer, Propagator.NOOP).recordValidatedCorrelation(PUBLIC_CORRELATION_ID);

        assertTrue(capturedLogs.list.isEmpty());
    }

    private static JsonNode ecsJson(ILoggingEvent event) {
        LoggerContext loggingContext = new LoggerContext();
        loggingContext.putObject(Environment.class.getName(), new MockEnvironment());
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(loggingContext);
        encoder.setFormat("ecs");
        encoder.start();
        try {
            return new JsonMapper().readTree(encoder.encode(event));
        } finally {
            encoder.stop();
            loggingContext.stop();
        }
    }

    /**
     * Captures completed span names synchronously for exact stage-name assertions.
     *
     * @author Daniel Jr Nkulu
     */
    private static final class CapturingSpanExporter implements SpanExporter {
        private final List<String> spanNames = new CopyOnWriteArrayList<>();
        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            spans.forEach(span -> spanNames.add(span.getName()));
            this.spans.addAll(spans);
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
