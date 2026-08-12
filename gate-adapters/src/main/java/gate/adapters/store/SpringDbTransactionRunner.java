package gate.adapters.store;

import gate.ports.DbTransactionRunner;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DbTransactionRunner} over Spring's {@link TransactionTemplate} (§4.4: TransactionTemplate,
 * not JPA).
 *
 * <p>Because the port only accepts a {@link Supplier}, callers cannot smuggle a git invocation into
 * the transaction body — invariant I3 ("no SQLite transaction spans a ProcessBuilder call") is
 * therefore enforced by the signature rather than by review vigilance.
 */
public final class SpringDbTransactionRunner implements DbTransactionRunner {

    private final TransactionTemplate transactionTemplate;

    public SpringDbTransactionRunner(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }
}
