package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

/**
 * Carries nullable bounded W3C context across the durable outbox scheduling boundary.
 *
 * @param traceparent canonical W3C parent or null when no request span existed
 * @param tracestate bounded W3C vendor state or null
 * @author Daniel Jr Nkulu
 */
public record OutboxTraceContext(String traceparent, String tracestate) {
    /**
     * Returns an explicitly absent durable trace context.
     *
     * @return empty context
     */
    public static OutboxTraceContext empty() {
        return new OutboxTraceContext(null, null);
    }
}
