package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the stable, redacted Problem Details extension defined by the OpenAPI contract.
 *
 * @param type stable problem type URI
 * @param title human-readable problem category
 * @param status HTTP response status
 * @param detail redacted human-readable detail
 * @param instance request target associated with the problem
 * @param code stable application error code
 * @param correlationId safe request correlation identity
 * @param fieldErrors bounded validation errors keyed by field
 * @author Daniel Jr Nkulu
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemResponse(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        String correlationId,
        Map<String, List<String>> fieldErrors) {

    /** Defensively freezes nested validation details at the transport boundary. */
    public ProblemResponse {
        fieldErrors = immutableFieldErrors(fieldErrors);
    }

    @Override
    public Map<String, List<String>> fieldErrors() {
        return immutableFieldErrors(fieldErrors);
    }

    private static Map<String, List<String>> immutableFieldErrors(Map<String, List<String>> source) {
        if (source == null) {
            return null;
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((field, errors) -> copy.put(field, List.copyOf(errors)));
        return Map.copyOf(copy);
    }
}
