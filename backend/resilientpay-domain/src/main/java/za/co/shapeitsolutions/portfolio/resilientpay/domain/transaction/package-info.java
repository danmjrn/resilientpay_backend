/**
 * Owns the original-transaction aggregate and its reservation lifecycle. The reservation prevents
 * concurrent or duplicate full reversals and remains held while execution is ambiguous.
 *
 * @author Daniel Jr Nkulu
 */
package za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction;
