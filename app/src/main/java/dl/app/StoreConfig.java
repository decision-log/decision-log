package dl.app;

import dl.app.store.JooqStores;
import dl.app.store.JooqUnitOfWork;
import dl.domain.ports.단위작업;
import dl.domain.ports.저장소.*;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 배선은 여기 한 곳. Boot 자동설정이 준 DSLContext 를 그대로 쓴다. */
@Configuration
public class StoreConfig {
    @Bean 회의저장소 회의저장소(DSLContext db) { return new JooqStores.회의들(db); }
    @Bean 이슈저장소 이슈저장소(DSLContext db) { return new JooqStores.이슈들(db); }
    @Bean 용어집저장소 용어집저장소(DSLContext db) { return new JooqStores.용어집(db); }
    @Bean 명단저장소 명단저장소(DSLContext db) { return new JooqStores.명단들(db); }
    @Bean 단위작업 단위작업(DSLContext db) { return new JooqUnitOfWork(db); }
}
