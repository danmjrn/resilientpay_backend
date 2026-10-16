package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

/**
 * Verifies safe W3C trace extraction/injection and deliberate baggage suppression.
 *
 * @author Daniel Jr Nkulu
 */
class W3cTraceContextTest {
    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void validTraceContextIsForwardedWithoutBaggage() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("traceparent", TRACE_PARENT);
        headers.put("tracestate", "vendor=value,other=opaque");
        headers.put("baggage", "account-token=must-not-propagate");

        W3cTraceContext context = W3cTraceContext.from(headers);
        MessageProperties outcome = new MessageProperties();
        context.inject(outcome);

        assertEquals(TRACE_PARENT, outcome.getHeader("traceparent"));
        assertEquals("vendor=value,other=opaque", outcome.getHeader("tracestate"));
        assertFalse(outcome.getHeaders().containsKey("baggage"));
    }

    @Test
    void malformedOrOrphanedTraceStateFailsClosed() {
        assertThrows(
                InvalidInboundMessageException.class,
                () -> W3cTraceContext.from(
                        Map.of("traceparent", "00-00000000000000000000000000000000-00f067aa0ba902b7-01")));
        assertThrows(
                InvalidInboundMessageException.class, () -> W3cTraceContext.from(Map.of("tracestate", "vendor=value")));
        assertThrows(
                InvalidInboundMessageException.class,
                () -> W3cTraceContext.from(
                        Map.of("traceparent", TRACE_PARENT, "tracestate", "vendor=value\nforged=header")));
    }
}
