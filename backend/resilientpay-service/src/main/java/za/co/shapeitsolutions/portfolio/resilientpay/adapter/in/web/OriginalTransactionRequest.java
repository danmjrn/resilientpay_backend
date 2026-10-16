package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;

/**
 * Validates one synthetic original transaction used by the portfolio demonstration.
 *
 * @param transactionToken opaque synthetic transaction token
 * @param operationType operation type the synthetic original represents
 * @param amount exact original monetary amount
 * @param currency ISO 4217 currency code
 * @author Daniel Jr Nkulu
 */
public record OriginalTransactionRequest(
        @NotBlank @Pattern(regexp = "^synth-[a-z0-9-]{8,64}$")
        String transactionToken,

        @NotNull OperationType operationType,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("999999999999999.99")
        @Digits(integer = 15, fraction = 2)
        BigDecimal amount,

        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
