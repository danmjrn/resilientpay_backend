package za.co.shapeitsolutions.portfolio.resilientpay.adapter.out.persistence;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.shapeitsolutions.portfolio.resilientpay.application.port.out.TransactionBoundary;

/**
 * Adapts application-selected transaction semantics to Spring's transaction manager. This adapter
 * supplies mechanics only: application use cases choose the scope and exclude network work.
 *
 * @author Daniel Jr Nkulu
 */
public final class SpringTransactionBoundary implements TransactionBoundary {
    private final TransactionTemplate required;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate requiresNew;

    /** Creates immutable scope templates around the deployment's database transaction manager. */
    public SpringTransactionBoundary(PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager);
        required = new TransactionTemplate(transactionManager);
        readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T required(Supplier<T> work) {
        return required.execute(ignored -> work.get());
    }

    @Override
    public <T> T readOnly(Supplier<T> work) {
        return readOnly.execute(ignored -> work.get());
    }

    @Override
    public <T> T requiresNew(Supplier<T> work) {
        return requiresNew.execute(ignored -> work.get());
    }
}
