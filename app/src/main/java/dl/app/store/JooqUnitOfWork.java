package dl.app.store;

import dl.domain.ports.단위작업;
import org.jooq.DSLContext;

import java.util.function.Supplier;

/** jOOQ 트랜잭션. 도메인은 이게 트랜잭션인지 모른다. */
public final class JooqUnitOfWork implements 단위작업 {
    private final DSLContext db;
    public JooqUnitOfWork(DSLContext db) { this.db = db; }

    @Override public <T> T 안에서(Supplier<T> 묶음) {
        return db.transactionResult(cfg -> 묶음.get());
    }
}
