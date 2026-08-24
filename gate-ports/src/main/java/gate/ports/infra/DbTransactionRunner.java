package gate.ports.infra;

import java.util.function.Supplier;

/**
 * Runs a <em>pure-DB</em> unit of work in a single SQLite transaction (架构落地执行文档 §9.3 R-LOCK).
 *
 * <p>The functional argument is deliberately a plain {@link Supplier}: there is no way to hand it a
 * {@link ProcessRunner}, so the structural rule "a SQLite transaction never spans a
 * {@code ProcessBuilder} call" (I3) is enforced by what the type can express, not by discipline. Git
 * work always happens outside the {@code inTransaction} boundary.
 */
public interface DbTransactionRunner {

    <T> T inTransaction(Supplier<T> work);

    default void inTransaction(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
