package za.co.shapeitsolutions.portfolio.resilientpay.application.port.out;

import java.util.function.Supplier;

/**
 * Executes application-selected database units of work without exposing a persistence framework.
 * Failed work rolls back; independent work suspends an enclosing unit and commits separately.
 * Callers must keep network calls and admission checks outside these bounded database scopes.
 *
 * @author Daniel Jr Nkulu
 */
public interface TransactionBoundary {
    /** Joins an existing write unit or creates one; only a newly created unit commits on return. */
    <T> T required(Supplier<T> work);

    /** Runs a read-only unit, retaining the transaction adapter's normal isolation semantics. */
    <T> T readOnly(Supplier<T> work);

    /** Suspends any enclosing unit so durable evidence survives the enclosing rollback. */
    <T> T requiresNew(Supplier<T> work);

    /** Executes write work that has no return value. */
    default void required(Runnable work) {
        required(() -> {
            work.run();
            return null;
        });
    }

    /** Executes read-only work that has no return value. */
    default void readOnly(Runnable work) {
        readOnly(() -> {
            work.run();
            return null;
        });
    }

    /** Independently commits work that has no return value. */
    default void requiresNew(Runnable work) {
        requiresNew(() -> {
            work.run();
            return null;
        });
    }
}
