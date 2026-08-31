package dl.app.store;

import dl.domain.ports.UnitOfWork;
import org.jooq.DSLContext;

import java.util.function.Supplier;

/** jOOQ 트랜잭션. 도메인은 이게 트랜잭션인지 모른다. */
public final class JooqUnitOfWork implements UnitOfWork {
    private final DSLContext db;
    public JooqUnitOfWork(DSLContext db) { this.db = db; }

    @Override public <T> T within(Supplier<T> block) {
        return db.transactionResult(cfg -> block.get());
    }
}
