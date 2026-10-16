package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import za.co.shapeitsolutions.portfolio.resilientpay.application.model.DemonstrationFailure;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;

/**
 * Validates the canonical full-reversal acceptance JSON body.
 *
 * @param originalTransactionToken opaque token for the synthetic original transaction
 * @param operationType requested reversal operation type
 * @param amount exact monetary amount copied from the original transaction
 * @param currency ISO 4217 currency code copied from the original transaction
 * @param reason bounded operator-supplied reversal reason
 * @param demonstrationFailure optional local-demo failure scenario
 * @author Daniel Jr Nkulu
 */
public record AcceptReversalRequest(
        @NotBlank @Pattern(regexp = "^synth-[a-z0-9-]{8,64}$")
        String originalTransactionToken,

        @NotNull OperationType operationType,

        @NotNull
        @DecimalMin(value = "0", inclusive = false)
        @DecimalMax("999999999999999.99")
        @Digits(integer = 15, fraction = 2)
        BigDecimal amount,

        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
        @NotBlank @Size(min = 3, max = 120) String reason,
        DemonstrationFailure demonstrationFailure) {}
