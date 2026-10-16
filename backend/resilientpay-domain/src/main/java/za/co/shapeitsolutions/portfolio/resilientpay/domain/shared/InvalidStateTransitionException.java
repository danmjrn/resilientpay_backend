package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

/**
 * Signals an attempted aggregate transition that is not permitted by its current lifecycle state.
 * It is the explicit domain boundary for stale, reordered or otherwise invalid workflow commands.
 *
 * @author Daniel Jr Nkulu
 */
public final class InvalidStateTransitionException extends DomainException {

    /**
     * Creates a transition failure naming the aggregate and attempted transition.
     *
     * @param aggregate aggregate name
     * @param currentState current lifecycle state
     * @param transition attempted transition
     */
    public InvalidStateTransitionException(String aggregate, Object currentState, String transition) {
        super(
                DomainErrorCode.STATE_TRANSITION_NOT_ALLOWED,
                "%s cannot perform %s while in %s".formatted(aggregate, transition, currentState));
    }
}
