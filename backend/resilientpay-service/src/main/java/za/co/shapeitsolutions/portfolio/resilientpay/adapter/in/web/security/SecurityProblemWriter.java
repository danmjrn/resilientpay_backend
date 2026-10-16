package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

/**
 * Writes a minimal redacted Problem Details body before MVC exception handling is available.
 *
 * @author Daniel Jr Nkulu
 */
final class SecurityProblemWriter {
    private SecurityProblemWriter() {}

    /** Writes one stable authentication or authorization problem. */
    static void write(HttpServletResponse response, int status, String title, String code) throws IOException {
        String correlationId = correlationId();
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(CorrelationIdFilter.HEADER_NAME, correlationId);
        String body = ("{\"type\":\"urn:resilientpay:problem:%s\",\"title\":\"%s\",\"status\":%d,"
                        + "\"code\":\"%s\",\"correlationId\":\"%s\"}")
                .formatted(code.toLowerCase().replace('_', '-'), title, status, code, correlationId);
        response.getWriter().write(body);
    }

    private static String correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return value == null ? UUID.randomUUID().toString() : value;
    }
}
