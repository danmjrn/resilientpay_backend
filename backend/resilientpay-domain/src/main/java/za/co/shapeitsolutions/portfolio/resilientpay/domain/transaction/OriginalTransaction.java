package za.co.shapeitsolutions.portfolio.resilientpay.domain.transaction;

import java.time.Instant;
import java.util.Objects;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainErrorCode;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.DomainException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.InvalidStateTransitionException;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.Money;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OperationType;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.OriginalTransactionId;
import za.co.shapeitsolutions.portfolio.resilientpay.domain.shared.TenantId;

/**
 * Models a synthetic posted financial transaction that can be fully reversed at most once. The
 * immutable aggregate exposes explicit reservation transitions so ambiguity never releases the
 * duplicate-action guard accidentally.
 *
 * @author Daniel Jr Nkulu
 */
public final class OriginalTransaction {
    private final OriginalTransactionId id;
    private final TenantId tenantId;
    private final String externalReferenceToken;
    private final OperationType operationType;
    private final Money money;
    private final OriginalTransactionState state;
    private final Instant occurredAt;
    private final Instant updatedAt;
    private final long version;

    private OriginalTransaction(
            OriginalTransactionId id,
            TenantId tenantId,
            String externalReferenceToken,
            OperationType operationType,
            Money money,
            OriginalTransactionState state,
            Instant occurredAt,
            Instant updatedAt,
            long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.externalReferenceToken = validateToken(externalReferenceToken);
        this.operationType = Objects.requireNonNull(operationType, "operationType must not be null");
        this.money = Objects.requireNonNull(money, "money must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(occurredAt)) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "updatedAt must not precede occurredAt");
        }
        if (version < 0) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "version must not be negative");
        }
        this.version = version;
    }

    /**
     * Creates a newly posted synthetic original transaction.
     *
     * @param id transaction identifier
     * @param tenantId owning tenant
     * @param externalReferenceToken tokenised external reference
     * @param operationType neutral operation type
     * @param money posted amount and currency
     * @param occurredAt posting time
     * @return posted aggregate
     */
    public static OriginalTransaction post(
            OriginalTransactionId id,
            TenantId tenantId,
            String externalReferenceToken,
            OperationType operationType,
            Money money,
            Instant occurredAt) {
        return new OriginalTransaction(
                id,
                tenantId,
                externalReferenceToken,
                operationType,
                money,
                OriginalTransactionState.POSTED,
                occurredAt,
                occurredAt,
                0);
    }

    /**
     * Reconstitutes a previously validated aggregate from an outbound persistence adapter.
     *
     * @return restored aggregate
     */
    public static OriginalTransaction restore(
            OriginalTransactionId id,
            TenantId tenantId,
            String externalReferenceToken,
            OperationType operationType,
            Money money,
            OriginalTransactionState state,
            Instant occurredAt,
            Instant updatedAt,
            long version) {
        return new OriginalTransaction(
                id, tenantId, externalReferenceToken, operationType, money, state, occurredAt, updatedAt, version);
    }

    /**
     * Reserves this transaction for a single reversal. Database locking and uniqueness complement
     * this aggregate check at the transaction boundary.
     *
     * @param at transition time
     * @return reserved aggregate
     */
    public OriginalTransaction reserveForReversal(Instant at) {
        requireState(OriginalTransactionState.POSTED, "reserveForReversal");
        return transitionTo(OriginalTransactionState.REVERSAL_RESERVED, at);
    }

    /**
     * Confirms that the reserved original was reversed downstream.
     *
     * @param at confirmation time
     * @return terminal reversed aggregate
     */
    public OriginalTransaction confirmReversed(Instant at) {
        requireState(OriginalTransactionState.REVERSAL_RESERVED, "confirmReversed");
        return transitionTo(OriginalTransactionState.REVERSED, at);
    }

    /**
     * Releases a reservation only after non-execution has been established by the workflow.
     * Callers must never invoke this transition for an ambiguous downstream outcome.
     *
     * @param at release time
     * @return posted aggregate available for a later reversal
     */
    public OriginalTransaction releaseReservation(Instant at) {
        requireState(OriginalTransactionState.REVERSAL_RESERVED, "releaseReservation");
        return transitionTo(OriginalTransactionState.POSTED, at);
    }

    /** Returns the aggregate identifier. */
    public OriginalTransactionId id() {
        return id;
    }

    /** Returns the owning tenant. */
    public TenantId tenantId() {
        return tenantId;
    }

    /** Returns the non-sensitive tokenised external reference. */
    public String externalReferenceToken() {
        return externalReferenceToken;
    }

    /** Returns the neutral operation type. */
    public OperationType operationType() {
        return operationType;
    }

    /** Returns the original full amount and currency. */
    public Money money() {
        return money;
    }

    /** Returns the current reservation lifecycle state. */
    public OriginalTransactionState state() {
        return state;
    }

    /** Returns the synthetic posting time. */
    public Instant occurredAt() {
        return occurredAt;
    }

    /** Returns the last domain transition time. */
    public Instant updatedAt() {
        return updatedAt;
    }

    /** Returns the optimistic aggregate version. */
    public long version() {
        return version;
    }

    private OriginalTransaction transitionTo(OriginalTransactionState target, Instant at) {
        Objects.requireNonNull(at, "transition time must not be null");
        if (at.isBefore(updatedAt)) {
            throw new DomainException(DomainErrorCode.INVALID_VALUE, "transition time must not move backwards");
        }
        return new OriginalTransaction(
                id, tenantId, externalReferenceToken, operationType, money, target, occurredAt, at, version + 1);
    }

    private void requireState(OriginalTransactionState expected, String transition) {
        if (state != expected) {
            throw new InvalidStateTransitionException("OriginalTransaction", state, transition);
        }
    }

    private static String validateToken(String value) {
        String token = Objects.requireNonNull(value, "externalReferenceToken must not be null")
                .strip();
        if (token.length() < 8 || token.length() > 128 || token.contains(" ")) {
            throw new DomainException(
                    DomainErrorCode.INVALID_VALUE, "externalReferenceToken must contain 8 to 128 non-space characters");
        }
        return token;
    }
}
