package za.co.shapeitsolutions.portfolio.resilientpay.bootstrap.config;

/**
 * Captures immutable profile-derived runtime capabilities at composition time.
 *
 * @param demonstrationFailuresAllowed whether synthetic failure controls may be honored
 * @author Daniel Jr Nkulu
 */
public record RuntimeMode(boolean demonstrationFailuresAllowed) {}
