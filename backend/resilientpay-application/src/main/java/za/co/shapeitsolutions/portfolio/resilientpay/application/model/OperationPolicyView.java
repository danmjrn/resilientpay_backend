package za.co.shapeitsolutions.portfolio.resilientpay.application.model;

import java.util.Objects;
import java.util.UUID;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.policy.OperationPolicyState;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;

/**
 * Provides the active policy fields needed to explain bounded retry behaviour.
 *
 * @param id immutable policy-version identity
 * @param operationType operation category governed by the policy
 * @param policyVersion monotonically increasing version within the operation category
 * @param status policy lifecycle state
 * @param maxAttempts snapshotted upper bound for execution attempts
 * @param timeoutMs snapshotted result timeout in milliseconds
 * @author Daniel Jr Nkulu
 */
public record OperationPolicyView(
        UUID id,
        OperationType operationType,
        int policyVersion,
        OperationPolicyState status,
        int maxAttempts,
        long timeoutMs) {
    public OperationPolicyView {
        Objects.requireNonNull(id);
        Objects.requireNonNull(operationType);
        Objects.requireNonNull(status);
    }
}
