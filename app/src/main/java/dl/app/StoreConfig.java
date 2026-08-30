package dl.app;

import dl.app.job.JooqJobs;
import dl.app.job.가짜처리;
import dl.app.job.잡실행기;
import dl.app.job.처리;
import dl.app.store.JooqMeetings;
import dl.app.store.JooqStores;
import dl.app.store.JooqUnitOfWork;
import dl.domain.ports.단위작업;
import dl.domain.ports.저장소.*;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
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

    // 회의 · 잡은 도메인 포트가 아니다 — 잡 기계장치는 전부 app 에 산다 (설계 합의문 "거처")
    @Bean JooqMeetings 회의들(DSLContext db) { return new JooqMeetings(db); }
    @Bean JooqJobs 잡들(DSLContext db) { return new JooqJobs(db); }

    /** 지금은 잠깐 돌다 끝나는 가짜. 뒤 티켓이 이 자리에 진짜 전사·추출을 꽂는다. */
    @Bean 처리 처리(@Value("${dl.processing.step-millis}") long 단계간격) { return new 가짜처리(단계간격); }

    @Bean 잡실행기 잡실행기(JooqJobs 잡들, 처리 처리, @Value("${dl.job.workers}") int 일꾼수) {
        return new 잡실행기(잡들, 처리, 일꾼수);
    }
}
