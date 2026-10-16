package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

/**
 * Holds the stable pseudonymous identity used for maker-checker and audit decisions. It must not
 * contain a display name, token or other sensitive identity payload.
 *
 * @param value pseudonymous identity value
 * @author Daniel Jr Nkulu
 */
public record ActorId(String value) {
    public ActorId {
        value = DomainChecks.boundedText(value, "actorId", 1, 128);
    }
}
