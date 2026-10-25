package za.co.shapeitsolutions.portfolio.resilientpay;

import java.util.HexFormat;

/**
 * Generates openly predictable fixture bytes for isolated tests, never production credentials.
 * These sequential bytes deliberately contain no external or privately generated key material.
 *
 * @author Daniel Jr Nkulu
 */
public final class PortfolioTestKeys {
    private PortfolioTestKeys() {}

    /** Returns a public 32-byte fixture sequence beginning at the supplied byte offset. */
    public static String sequentialHex(int offset) {
        byte[] fixture = new byte[32];
        for (int index = 0; index < fixture.length; index++) {
            fixture[index] = (byte) (offset + index);
        }
        return HexFormat.of().formatHex(fixture);
    }
}
