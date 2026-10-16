package za.co.shapeitsolutions.portfolio.resilientpay.domain.shared;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Models a positive monetary value with an explicit ISO currency. Amounts are canonicalised to
 * avoid scale-only inequality, and floating-point values never enter the financial domain.
 *
 * @param amount strictly positive decimal amount
 * @param currency ISO currency
 * @author Daniel Jr Nkulu
 */
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        DomainChecks.required(amount, "amount");
        DomainChecks.required(currency, "currency");
        if (amount.signum() <= 0) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "amount must be positive");
        }
        if (amount.scale() > 4) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "amount must have at most four fractional digits");
        }
        amount = amount.stripTrailingZeros();
    }

    /**
     * Tests financial equality independently of source decimal scale.
     *
     * @param other value to compare
     * @return true when amount and currency match
     */
    public boolean sameValueAs(Money other) {
        return other != null && currency.equals(other.currency) && amount.compareTo(other.amount) == 0;
    }
}
