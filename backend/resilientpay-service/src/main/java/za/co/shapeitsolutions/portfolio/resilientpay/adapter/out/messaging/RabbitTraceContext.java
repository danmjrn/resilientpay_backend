package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Propagates only bounded W3C trace context across RabbitMQ. Baggage, payloads, tenant identifiers,
 * and workflow identifiers are deliberately excluded from trace tags and propagation.
 *
 * @author Daniel Jr Nkulu
 */
@Component
@Profile("!test")
public final class RabbitTraceContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitTraceContext.class);
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final Pattern SIMPLE_TRACESTATE_KEY = Pattern.compile("^[a-z][a-z0-9_*/-]{0,255}$");
    private static final Pattern MULTI_TENANT_TRACESTATE_KEY =
            Pattern.compile("^[a-z0-9][a-z0-9_*/-]{0,240}@[a-z][a-z0-9_*/-]{0,13}$");
    private static final int MAX_TRACESTATE_LENGTH = 512;
    private static final int MAX_TRACESTATE_MEMBERS = 32;

    private final Tracer tracer;
    private final Propagator propagator;

    /**
     * Creates the W3C-only Rabbit trace boundary.
     *
     * @param tracer Micrometer tracing bridge
     * @param propagator configured W3C propagator
     */
    public RabbitTraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
        this.propagator = Objects.requireNonNull(propagator, "propagator must not be null");
    }

    /**
     * Starts a producer span and injects its W3C context into safe AMQP headers.
     *
     * @param properties outbound AMQP properties
     * @param eventType bounded event type used as a low-cardinality span tag
     * @return closeable producer span scope
     */
    public TraceScope startProducer(MessageProperties properties, String eventType, OutboxTraceContext durableContext) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(durableContext, "durableContext must not be null");
        Span.Builder builder;
        if (durableContext.traceparent() == null) {
            builder = tracer.spanBuilder();
        } else {
            Map<String, String> carrier = new LinkedHashMap<>();
            carrier.put("traceparent", durableContext.traceparent());
            if (durableContext.tracestate() != null) {
                carrier.put("tracestate", durableContext.tracestate());
            }
            builder = propagator.extract(carrier, RabbitTraceContext::getW3cHeader);
        }
        Span span = builder.name("OUTBOX_PUBLISH")
                .kind(Span.Kind.PRODUCER)
                .tag("messaging.system", "rabbitmq")
                .tag("messaging.operation.type", "publish")
                .tag("messaging.message.type", eventType)
                .start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        propagator.inject(span.context(), properties, RabbitTraceContext::setW3cHeader);
        return new TraceScope(span, scope);
    }

    /**
     * Captures the current request/message span as bounded W3C text for transactional persistence.
     *
     * @return durable context, or an empty value when no span is active
     */
    public OutboxTraceContext captureCurrent() {
        Span current = tracer.currentSpan();
        if (current == null || current.isNoop()) {
            return OutboxTraceContext.empty();
        }
        Map<String, String> carrier = new LinkedHashMap<>();
        propagator.inject(current.context(), carrier, RabbitTraceContext::setW3cHeader);
        String traceparent = carrier.get("traceparent");
        String tracestate = carrier.get("tracestate");
        return valid(traceparent, tracestate)
                ? new OutboxTraceContext(traceparent, tracestate)
                : OutboxTraceContext.empty();
    }

    /**
     * Starts a consumer span parented from a previously validated W3C header pair.
     *
     * @param properties inbound AMQP properties
     * @param eventType bounded event type used as a low-cardinality span tag
     * @return closeable consumer span scope
     */
    public TraceScope startConsumer(MessageProperties properties, String eventType) {
        Objects.requireNonNull(properties, "properties must not be null");
        Map<String, Object> headers = properties.getHeaders();
        Span.Builder builder = text(headers.get("traceparent")).isEmpty()
                ? tracer.spanBuilder().setNoParent()
                : propagator.extract(headers, RabbitTraceContext::getW3cHeader);
        String spanName = "ReversalOutcomeObserved.v1".equals(eventType) ? "OUTCOME_CONSUME" : "LIFECYCLE_CONSUME";
        Span span = builder.name(spanName)
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.system", "rabbitmq")
                .tag("messaging.operation.type", "process")
                .tag("messaging.message.type", eventType)
                .start();
        return new TraceScope(span, tracer.withSpan(span));
    }

    /**
     * Links an authenticated public correlation identifier to the active trace in structured logs.
     * Call only after envelope and payload validation. This records an observed link, not a committed
     * transaction, successful execution, acknowledgement, or completed workflow. No message content,
     * domain identifiers, or new trace tags are emitted.
     *
     * @param correlationId canonical public identifier from the validated envelope
     */
    public void recordValidatedCorrelation(UUID correlationId) {
        Span current = tracer.currentSpan();
        if (correlationId == null || current == null || current.isNoop()) {
            return;
        }
        String traceId = current.context().traceId();
        if (traceId == null
                || !TRACE_ID.matcher(traceId).matches()
                || traceId.chars().allMatch(value -> value == '0')) {
            return;
        }
        LOGGER.atInfo()
                .addKeyValue("correlationId", correlationId.toString())
                .addKeyValue("w3cTraceId", traceId)
                .log("PUBLIC_CORRELATION_TRACE_LINK");
    }

    /**
     * Checks bounded canonical W3C syntax without treating trace context as domain authority.
     *
     * @param properties inbound AMQP properties
     * @return true when absent or syntactically safe and canonical
     */
    public boolean isValid(MessageProperties properties) {
        Map<String, Object> headers = properties.getHeaders();
        String traceparent = text(headers.get("traceparent"));
        String tracestate = text(headers.get("tracestate"));
        return valid(traceparent.isEmpty() ? null : traceparent, tracestate.isEmpty() ? null : tracestate);
    }

    private static boolean valid(String traceparent, String tracestate) {
        if (traceparent == null) {
            return tracestate == null;
        }
        java.util.regex.Matcher matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()
                || matcher.group(1).chars().allMatch(value -> value == '0')
                || matcher.group(2).chars().allMatch(value -> value == '0')) {
            return false;
        }
        return tracestate == null || validTraceState(tracestate);
    }

    private static boolean validTraceState(String value) {
        if (value.length() > MAX_TRACESTATE_LENGTH || value.startsWith(",") || value.endsWith(",")) {
            return false;
        }
        String[] members = value.split(",", -1);
        if (members.length > MAX_TRACESTATE_MEMBERS) {
            return false;
        }
        for (String member : members) {
            int delimiter = member.indexOf('=');
            if (delimiter <= 0 || delimiter != member.lastIndexOf('=') || delimiter == member.length() - 1) {
                return false;
            }
            String key = member.substring(0, delimiter);
            String memberValue = member.substring(delimiter + 1);
            if ((!SIMPLE_TRACESTATE_KEY.matcher(key).matches()
                            && !MULTI_TENANT_TRACESTATE_KEY.matcher(key).matches())
                    || memberValue.length() > 256
                    || memberValue.endsWith(" ")) {
                return false;
            }
            for (int index = 0; index < memberValue.length(); index++) {
                char character = memberValue.charAt(index);
                if (character < 0x20 || character > 0x7e || character == ',' || character == '=') {
                    return false;
                }
            }
        }
        return true;
    }

    private static <C> void setW3cHeader(C carrier, String key, String value) {
        String canonicalKey = key.toLowerCase(Locale.ROOT);
        if (("traceparent".equals(canonicalKey) || "tracestate".equals(canonicalKey)) && value != null) {
            if (carrier instanceof MessageProperties properties) {
                properties.setHeader(canonicalKey, value);
            } else if (carrier instanceof Map<?, ?> values) {
                @SuppressWarnings("unchecked")
                Map<String, String> stringValues = (Map<String, String>) values;
                stringValues.put(canonicalKey, value);
            }
        }
    }

    private static String getW3cHeader(Map<String, ?> carrier, String key) {
        String canonicalKey = key.toLowerCase(Locale.ROOT);
        return "traceparent".equals(canonicalKey) || "tracestate".equals(canonicalKey)
                ? text(carrier.get(canonicalKey))
                : null;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /**
     * Owns one message span and its thread-local scope.
     *
     * @author Daniel Jr Nkulu
     */
    public static final class TraceScope implements AutoCloseable {
        private final Span span;
        private final Tracer.SpanInScope scope;

        private TraceScope(Span span, Tracer.SpanInScope scope) {
            this.span = span;
            this.scope = scope;
        }

        /**
         * Marks the span failed using only the exception type and stack, never message content.
         *
         * @param failure adapter failure
         */
        public void error(Throwable failure) {
            span.error(failure);
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }
}
