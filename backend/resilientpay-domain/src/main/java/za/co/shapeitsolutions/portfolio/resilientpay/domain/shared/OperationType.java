package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

/**
 * Enumerates the four neutral synthetic operation types used by the portfolio demonstration. The
 * names are clean-room assumptions and do not reproduce a private payment taxonomy.
 *
 * @author Daniel Jr Nkulu
 */
public enum OperationType {
    CARD_PURCHASE,
    ACCOUNT_TRANSFER,
    CASH_WITHDRAWAL,
    MERCHANT_PAYOUT
}
