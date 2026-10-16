package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies the HTTP correlation filter preserves valid caller identity and explicitly replaces
 * malformed or absent values with a safe UUID. The downstream chain sees only that safe identity,
 * and request-local MDC is cleared on both normal completion and propagated servlet failure.
 *
 * @author Daniel Jr Nkulu
 */
class CorrelationIdFilterTest {

    @AfterEach
    void clearRequestContext() {
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void validCallerCorrelationIsPreservedDuringTheChainAndInTheResponse() throws Exception {
        String correlation = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reversalcommands");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, correlation);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter()
                .doFilter(
                        request,
                        response,
                        (incoming, outgoing) -> assertEquals(correlation, MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertEquals(correlation, response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void absentCorrelationCreatesASafeRequestLocalIdentity() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reversalcommands");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CorrelationIdFilter()
                .doFilter(
                        request,
                        response,
                        (incoming, outgoing) -> assertEquals(
                                response.getHeader(CorrelationIdFilter.HEADER_NAME),
                                MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertCanonicalUuid(response);
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void malformedCallerValuesAreExplicitlyReplacedWithoutReflection() throws Exception {
        for (String supplied :
                List.of("", "synthetic-invalid-value", "<script>synthetic-canary</script>", "x".repeat(2000))) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reversalcommands");
            request.addHeader(CorrelationIdFilter.HEADER_NAME, supplied);
            MockHttpServletResponse response = new MockHttpServletResponse();

            new CorrelationIdFilter()
                    .doFilter(
                            request,
                            response,
                            (incoming, outgoing) -> assertEquals(
                                    response.getHeader(CorrelationIdFilter.HEADER_NAME),
                                    MDC.get(CorrelationIdFilter.MDC_KEY)));

            assertCanonicalUuid(response);
            assertFalse(supplied.equals(response.getHeader(CorrelationIdFilter.HEADER_NAME)));
            assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
        }
    }

    @Test
    void downstreamFailurePropagatesWhileRequestContextIsCleared() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reversalcommands");
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "synthetic-invalid-value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(
                ServletException.class,
                () -> new CorrelationIdFilter().doFilter(request, response, (incoming, outgoing) -> {
                    assertEquals(
                            response.getHeader(CorrelationIdFilter.HEADER_NAME), MDC.get(CorrelationIdFilter.MDC_KEY));
                    throw new ServletException("Synthetic downstream failure");
                }));

        assertCanonicalUuid(response);
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    private static void assertCanonicalUuid(MockHttpServletResponse response) {
        String correlation = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertEquals(UUID.fromString(correlation).toString(), correlation);
    }
}
