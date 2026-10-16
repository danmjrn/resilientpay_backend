package za.co.shapeitsolutions.portfolio.resilientpay.domain.policy;

/**
 * Defines the controlled lifecycle for a versioned operation policy. Only an approved active
 * version may govern newly accepted reversals.
 *
 * @author Daniel Jr Nkulu
 */
public enum OperationPolicyState {
    DRAFT,
    PENDING_APPROVAL,
    ACTIVE,
    RETIRED
}
