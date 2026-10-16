package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Validates the distinct checker's bounded approval purpose.
 *
 * @param purpose bounded checker-supplied approval purpose
 * @author Daniel Jr Nkulu
 */
public record ReconciliationApprovalRequest(
        @NotBlank @Size(min = 8, max = 160) String purpose) {}
