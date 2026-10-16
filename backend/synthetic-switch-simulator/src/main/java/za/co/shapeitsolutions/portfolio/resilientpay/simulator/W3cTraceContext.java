package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.amqp.core.MessageProperties;

/**
 * Validates and forwards only bounded W3C trace correlation headers. Baggage is deliberately not
 * accepted or injected because arbitrary caller metadata can contain sensitive values.
 *
 * @param traceparent validated W3C trace-parent header, or null when absent
 * @param tracestate validated bounded W3C vendor state, or null when absent
 * @author Daniel Jr Nkulu
 */
record W3cTraceContext(String traceparent, String tracestate) {
    private static final Pattern TRACE_PARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private static final Pattern TRACE_STATE_KEY =
            Pattern.compile("^[a-z0-9][_a-z0-9*/-]{0,255}(?:@[a-z0-9][_a-z0-9*/-]{0,13})?$");
    private static final int MAX_TRACE_STATE_LENGTH = 512;
    private static final int MAX_TRACE_STATE_MEMBERS = 32;

    /** Reads an optional, strictly bounded context from AMQP headers. */
    static W3cTraceContext from(Map<String, Object> headers) {
        String parent = text(headers.get("traceparent"));
        String state = text(headers.get("tracestate"));
        if (parent == null && state == null) {
            return new W3cTraceContext(null, null);
        }
        Matcher matcher = parent == null ? null : TRACE_PARENT.matcher(parent);
        if (matcher == null
                || !matcher.matches()
                || matcher.group(1).chars().allMatch(character -> character == '0')
                || matcher.group(2).chars().allMatch(character -> character == '0')
                || !validTraceState(state)) {
            throw new InvalidInboundMessageException("INVALID_TRACE_CONTEXT");
        }
        return new W3cTraceContext(parent, state);
    }

    /** Injects only validated trace correlation, never baggage. */
    void inject(MessageProperties properties) {
        if (traceparent == null) {
            return;
        }
        properties.setHeader("traceparent", traceparent);
        if (tracestate != null) {
            properties.setHeader("tracestate", tracestate);
        }
    }

    private static boolean validTraceState(String state) {
        if (state == null) {
            return true;
        }
        if (state.isBlank() || state.length() > MAX_TRACE_STATE_LENGTH) {
            return false;
        }
        String[] members = state.split(",", -1);
        if (members.length > MAX_TRACE_STATE_MEMBERS) {
            return false;
        }
        for (String member : members) {
            int separator = member.indexOf('=');
            if (separator < 1 || separator == member.length() - 1) {
                return false;
            }
            String key = member.substring(0, separator).strip();
            String value = member.substring(separator + 1);
            if (!TRACE_STATE_KEY.matcher(key).matches()
                    || value.length() > 256
                    || value.charAt(0) == ' '
                    || value.charAt(value.length() - 1) == ' '
                    || value.chars().anyMatch(character -> character < 0x20 || character > 0x7e || character == ',')) {
                return false;
            }
        }
        return true;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
