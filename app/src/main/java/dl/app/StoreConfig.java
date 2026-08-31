package dl.app;

import dl.adapters.extract.MarkerExtractAdapter;
import dl.adapters.stt.SimulatorSttAdapter;
import dl.adapters.stt.MeasuredCorruptionRules;
import dl.app.job.JooqJobs;
import dl.app.job.JobRunner;
import dl.app.job.Processing;
import dl.app.job.RoundProcessing;
import dl.app.store.JooqMeetings;
import dl.app.store.JooqStores;
import dl.app.store.JooqUnitOfWork;
import dl.domain.RoundOrchestrator;
import dl.domain.ports.ExtractPort;
import dl.domain.ports.SttPort;
import dl.domain.ports.UnitOfWork;
import dl.domain.ports.Stores.*;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 배선은 여기 한 곳. Boot 자동설정이 준 DSLContext 를 그대로 쓴다. */
@Configuration
public class StoreConfig {
    @Bean MeetingStore meetingStore(DSLContext db) { return new JooqStores.Meetings(db); }
    @Bean IssueStore issueStore(DSLContext db) { return new JooqStores.Issues(db); }
    @Bean GlossaryStore glossaryStore(DSLContext db) { return new JooqStores.Glossary(db); }
    @Bean RosterStore rosterStore(DSLContext db) { return new JooqStores.Roster(db); }
    @Bean UnitOfWork unitOfWork(DSLContext db) { return new JooqUnitOfWork(db); }

    // 회의 · 잡은 도메인 포트가 아니다 — 잡 기계장치는 전부 app 에 산다 (설계 합의문 "거처")
    @Bean JooqMeetings meetings(DSLContext db) { return new JooqMeetings(db); }
    @Bean JooqJobs jobs(DSLContext db) { return new JooqJobs(db); }

    // ── 경계 뒤의 어댑터 ────────────────────────────────────

    /**
     * 오염 시뮬레이터. 올린 파일이 대본이고, 컨텍스트에 있는 용어는 정확히 · 없는 용어는
     * 실측 규칙대로 깨진다 (ADR 0005). 진짜 공급자 어댑터는 #7 이 이 자리에 꽂는다.
     */
    @Bean SttPort sttPort(@Value("${dl.stt.simulator.seed}") long seed) {
        return new SimulatorSttAdapter(MeasuredCorruptionRules.all(), seed);
    }

    /**
     * 마커 대본 추출기. <b>프로덕션 경로에서 아직 불리지 않는다</b> —
     * 오케스트레이터 생성자가 요구해서 꽂아 둔 것이고, 추출 경계는 #5 의 산출물이다.
     */
    @Bean ExtractPort extractPort() { return new MarkerExtractAdapter(); }

    @Bean RoundOrchestrator roundOrchestrator(SttPort stt, ExtractPort extract, MeetingStore meetings,
                                              IssueStore issues, GlossaryStore glossary, UnitOfWork unit) {
        return new RoundOrchestrator(stt, extract, meetings, issues, glossary, unit);
    }

    /** 잡이 STT 경계를 넘는 자리. #3 이 세워 둔 가짜는 여기서 사라졌다. */
    @Bean Processing processing(RoundOrchestrator orchestrator) { return new RoundProcessing(orchestrator); }

    @Bean JobRunner jobRunner(JooqJobs jobs, Processing processing, @Value("${dl.job.workers}") int workers) {
        return new JobRunner(jobs, processing, workers);
    }
}
