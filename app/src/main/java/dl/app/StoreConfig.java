package dl.app;

import dl.adapters.extract.MarkerExtractAdapter;
import dl.adapters.stt.SimulatorSttAdapter;
import dl.adapters.stt.실측오염규칙;
import dl.app.job.JooqJobs;
import dl.app.job.잡실행기;
import dl.app.job.처리;
import dl.app.job.회차처리;
import dl.app.store.JooqMeetings;
import dl.app.store.JooqStores;
import dl.app.store.JooqUnitOfWork;
import dl.domain.회차오케스트레이터;
import dl.domain.ports.ExtractPort;
import dl.domain.ports.SttPort;
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

    // ── 경계 뒤의 어댑터 ────────────────────────────────────

    /**
     * 오염 시뮬레이터. 올린 파일이 대본이고, 컨텍스트에 있는 용어는 정확히 · 없는 용어는
     * 실측 규칙대로 깨진다 (ADR 0005). 진짜 공급자 어댑터는 #7 이 이 자리에 꽂는다.
     */
    @Bean SttPort sttPort(@Value("${dl.stt.simulator.seed}") long 시드) {
        return new SimulatorSttAdapter(실측오염규칙.전량(), 시드);
    }

    /**
     * 마커 대본 추출기. <b>프로덕션 경로에서 아직 불리지 않는다</b> —
     * 오케스트레이터 생성자가 요구해서 꽂아 둔 것이고, 추출 경계는 #5 의 산출물이다.
     */
    @Bean ExtractPort extractPort() { return new MarkerExtractAdapter(); }

    @Bean 회차오케스트레이터 회차오케스트레이터(SttPort stt, ExtractPort extract, 회의저장소 회의들,
                                              이슈저장소 이슈들, 용어집저장소 용어집, 단위작업 단위) {
        return new 회차오케스트레이터(stt, extract, 회의들, 이슈들, 용어집, 단위);
    }

    /** 잡이 STT 경계를 넘는 자리. #3 이 세워 둔 가짜는 여기서 사라졌다. */
    @Bean 처리 처리(회차오케스트레이터 오케) { return new 회차처리(오케); }

    @Bean 잡실행기 잡실행기(JooqJobs 잡들, 처리 처리, @Value("${dl.job.workers}") int 일꾼수) {
        return new 잡실행기(잡들, 처리, 일꾼수);
    }
}
