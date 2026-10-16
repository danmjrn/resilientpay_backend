package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

/**
 * Enumerates stable domain failure codes without coupling the domain to a transport error model.
 * Adapters may map these codes to Problem Details while preserving the business meaning.
 *
 * @author Daniel Jr Nkulu
 */
public enum DomainErrorCode {
    INVALID_VALUE,
    STATE_TRANSITION_NOT_ALLOWED,
    MONEY_MISMATCH,
    TENANT_SCOPE_MISMATCH,
    POLICY_NOT_ACTIVE,
    POLICY_APPROVER_CONFLICT,
    RETRY_NOT_PROVEN_SAFE,
    ATTEMPT_LIMIT_EXCEEDED,
    RECONCILIATION_APPROVER_CONFLICT
}
