package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Validates proof and purpose for one explicitly safe retry request.
 *
 * @param proof bounded proof that the prior attempt was not executed
 * @param purpose bounded operator-supplied retry purpose
 * @author Daniel Jr Nkulu
 */
public record RetryRequest(
        @NotBlank @Pattern(regexp = "^CONFIRMED_NOT_EXECUTED$")
        String proof,

        @NotBlank @Size(min = 8, max = 160) String purpose) {}
