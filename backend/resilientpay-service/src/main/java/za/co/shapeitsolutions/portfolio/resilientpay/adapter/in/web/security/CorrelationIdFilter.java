package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a validated correlation identifier for every request and response.
 * Invalid caller values are replaced rather than reflected into logs or downstream messages.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public final class CorrelationIdFilter extends OncePerRequestFilter {

    /** Header carrying the public correlation identifier. */
    public static final String HEADER_NAME = "X-Correlation-ID";

    /** MDC key used by structured logging configuration. */
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = validatedOrGenerated(request.getHeader(HEADER_NAME));
        response.setHeader(HEADER_NAME, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String validatedOrGenerated(String suppliedValue) {
        if (suppliedValue != null) {
            try {
                return UUID.fromString(suppliedValue).toString();
            } catch (IllegalArgumentException ignored) {
                // A malformed external value must not be propagated into trusted telemetry.
                return UUID.randomUUID().toString();
            }
        }
        return UUID.randomUUID().toString();
    }
}
