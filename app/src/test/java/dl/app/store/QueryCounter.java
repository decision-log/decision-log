package dl.app.store;

import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

import java.util.ArrayList;
import java.util.List;

/** 포트가 SQL 을 몇 번 때리는지 센다. 묶음 포트가 도로 하나씩으로 돌아가면 여기서 걸린다. */
public final class QueryCounter implements ExecuteListener {
    private final List<String> queries = new ArrayList<>();

    @Override public void executeStart(ExecuteContext ctx) {
        if (ctx.sql() != null) queries.add(ctx.sql().replaceAll("\\s+", " ").trim());
    }
    public int count() { return queries.size(); }
    public List<String> queries() { return List.copyOf(queries); }
    public void reset() { queries.clear(); }
}
