package dl.app.store;

import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

import java.util.ArrayList;
import java.util.List;

/** 포트가 SQL 을 몇 번 때리는지 센다. 묶음 포트가 도로 하나씩으로 돌아가면 여기서 걸린다. */
public final class QueryCounter implements ExecuteListener {
    private final List<String> 쿼리 = new ArrayList<>();

    @Override public void executeStart(ExecuteContext ctx) {
        if (ctx.sql() != null) 쿼리.add(ctx.sql().replaceAll("\\s+", " ").trim());
    }
    public int 횟수() { return 쿼리.size(); }
    public List<String> 쿼리() { return List.copyOf(쿼리); }
    public void 초기화() { 쿼리.clear(); }
}
