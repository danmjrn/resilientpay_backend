package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

/**
 * Defines the stable, transport-independent application failure vocabulary.
 *
 * @author Daniel Jr Nkulu
 */
public enum ApplicationErrorCode {
    INVALID_REQUEST,
    REVERSAL_NOT_ELIGIBLE,
    REVERSAL_ALREADY_EXISTS,
    IDEMPOTENCY_KEY_REUSED,
    POLICY_NOT_ACTIVE,
    STATE_TRANSITION_NOT_ALLOWED,
    TENANT_SCOPE_MISMATCH,
    RECONCILIATION_APPROVER_CONFLICT,
    AUTHENTICATION_REQUIRED,
    ACCESS_DENIED,
    RESOURCE_NOT_FOUND,
    RATE_LIMITED,
    DEPENDENCY_UNAVAILABLE,
    INTERNAL_ERROR
}
