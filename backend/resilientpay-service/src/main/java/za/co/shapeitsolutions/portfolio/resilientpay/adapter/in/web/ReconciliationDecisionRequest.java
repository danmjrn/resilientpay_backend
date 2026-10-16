package za.co.shapeitsolutions.portfolio.resilientpay.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Validates one evidence-backed maker decision using the public decision vocabulary.
 *
 * @param decision requested reconciliation proposal
 * @param purpose bounded maker-supplied decision purpose
 * @param evidenceDigest SHA-256 digest of the supporting evidence
 * @author Daniel Jr Nkulu
 */
public record ReconciliationDecisionRequest(
        @NotNull PublicReconciliationDecision decision,
        @NotBlank @Size(min = 8, max = 160) String purpose,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String evidenceDigest) {}
