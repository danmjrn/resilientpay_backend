package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the immutable Jackson writer used for bounded audit and outbox JSON serialization.
 *
 * @author Daniel Jr Nkulu
 */
public final class SafeJsonWriter {
    private static final ObjectWriter WRITER =
            JsonMapper.builder().findAndAddModules().build().writer();

    private SafeJsonWriter() {}

    /** Serializes one bounded value without mutating mapper configuration. */
    public static String write(Object value) throws JacksonException {
        return WRITER.writeValueAsString(value);
    }
}
