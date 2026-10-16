package za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction;

/**
 * Describes whether an original transaction is available, reserved for an in-flight reversal, or
 * irreversibly marked as reversed.
 *
 * @author Daniel Jr Nkulu
 */
public enum OriginalTransactionState {
    POSTED,
    REVERSAL_RESERVED,
    REVERSED
}
