package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns immutable Jackson readers and writers used at the synthetic-switch message boundary.
 *
 * @author Daniel Jr Nkulu
 */
final class SimulatorJsonCodec {
    private static final ObjectReader REQUEST_READER = JsonMapper.builder()
            .findAndAddModules()
            .build()
            .readerFor(ExecutionRequestedPayload.class)
            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final ObjectWriter OBJECT_WRITER =
            JsonMapper.builder().findAndAddModules().build().writer();

    private SimulatorJsonCodec() {}

    static ExecutionRequestedPayload readRequest(byte[] body) throws JacksonException {
        return REQUEST_READER.readValue(body);
    }

    static byte[] write(Object value) throws JacksonException {
        return OBJECT_WRITER.writeValueAsBytes(value);
    }
}
