package dl.app;

import dl.adapters.extract.MarkerExtractAdapter;
import dl.adapters.stt.SimulatorSttAdapter;
import dl.adapters.stt.SimulatorSttAdapter.Mode;
import dl.adapters.stt.SimulatorSttAdapter.Rule;
import dl.app.store.QueryCounter;
import dl.domain.RoundOrchestrator;
import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Audio;
import dl.domain.ports.UnitOfWork;
import dl.domain.ports.Stores.*;
import dl.script.Scripts;
import org.jooq.DSLContext;
import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

import static dl.app.jooq.Tables.EXTRACTION;
import static dl.app.jooq.Tables.ISSUE;
import static dl.app.jooq.Tables.ISSUE_CANDIDATE;
import static dl.app.jooq.Tables.MEETING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * 걷는 뼈대가 서 있는지 — 컨테이너 · 마이그레이션 · 생성 코드 · 배선 · HTTP 를 한 번에 지난다.
 *
 * 프로덕션과 **같은 배선**으로 돈다. 손으로 DSLContext 를 만들면 트랜잭션이 조용히
 * 무력화되는데, 그건 도메인에서도 회차 시뮬레이터에서도 안 보인다.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class WalkingSkeletonIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @TestConfiguration
    static class CounterWiring {
        @Bean QueryCounter queryCounter() { return new QueryCounter(); }
        @Bean ExecuteListenerProvider counter(QueryCounter c) { return new DefaultExecuteListenerProvider(c); }
    }

    @Autowired MeetingStore meetings;
    @Autowired ExtractionStore extractions;
    @Autowired IssueStore issues;
    @Autowired GlossaryStore glossary;
    @Autowired UnitOfWork unit;
    @Autowired QueryCounter counter;
    @Autowired DSLContext db;
    @Value("${local.server.port}") int port;

    /**
     * 가짜 STT 는 오디오가 아니라 대본을 받는다 (ADR 0005) — 그 자리가 Audio 다.
     * 대본은 리소스 한 벌이고 읽는 자리도 하나다 — 여기 인라인으로 박혀 있던 둘째 벌이 #5 에서 사라졌다.
     */
    static final String script = Scripts.defaultScript();

    /** 이 테스트가 재는 것은 오염이 아니라 배선이라, 규칙은 한 벌만 둔다. */
    static final List<Rule> rules = List.of(new Rule("Caddy", Mode.STEADY, List.of("캐디")));

    private RoundOrchestrator orchestrator(GlossaryStore g) {
        return new RoundOrchestrator(new SimulatorSttAdapter(rules, 7L),
                new MarkerExtractAdapter(), meetings, extractions, issues, g, unit);
    }

    private static Audio audio() {
        return new Audio(script.getBytes(java.nio.charset.StandardCharsets.UTF_8), "m.txt");
    }

    /** 회의를 만드는 것은 호출자의 일이다 — 오케스트레이터가 더 이상 안 만든다. */
    private RoundOrchestrator.RoundResult runOneRound(RoundOrchestrator o) {
        return o.run(meetings.newMeeting(), audio(), "1");
    }

    private static List<CandidateId> candidateIds(RoundOrchestrator.RoundResult r) {
        return r.extraction().issueCandidates().stream().map(IssueCandidate::id).toList();
    }

    /** 확인 화면이 하는 일 — 표기 교정은 호출자의 몫이고 여기선 안 고치고 그대로 올린다. */
    private static List<Term> terms(RoundOrchestrator.RoundResult r) {
        return r.extraction().termCandidates().stream()
                .map(t -> new Term(t.content().spelling(), t.content().meaning()))
                .toList();
    }

    private HttpResponse<String> GET(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 서버가_뜨고_화면과_API가_응답한다() throws Exception {
        var api = GET("/api/health");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).contains("\"ok\":true");

        var page = GET("/");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<div id=\"root\">");
    }

    @Test
    void 확인은_한_단위다() {
        var o = orchestrator(glossary);
        var r = runOneRound(o);
        int issuesBefore = issues.all().size(), termsBefore = glossary.all().size();

        var explodingGlossary = new GlossaryStore() {
            public void add(List<Term> ts) { throw new IllegalStateException("DB 끊김"); }
            public List<Term> all() { return glossary.all(); }
            public void edit(String oldSpelling, String newSpelling, String newMeaning) { glossary.edit(oldSpelling, newSpelling, newMeaning); }
        };

        Assertions.assertThrows(IllegalStateException.class, () ->
                orchestrator(explodingGlossary).confirm(candidateIds(r), terms(r)));

        assertThat(issues.all()).hasSize(issuesBefore);      // 승격이 되돌아갔다
        assertThat(glossary.all()).hasSize(termsBefore);
    }

    @Test
    void 확인이_후보_수만큼_왕복하지_않는다() {
        var o = orchestrator(glossary);
        var r = runOneRound(o);

        counter.reset();
        o.confirm(candidateIds(r), terms(r));

        // 승격 CTE 1방 + 용어 upsert 1방. 후보 수와 무관해야 한다.
        assertThat(counter.count())
                .as("확인() SQL: %s", counter.queries())
                .isLessThanOrEqualTo(2);
        assertThat(glossary.all()).extracting(Term::spelling).contains("툴 콜링", "스크럼");
    }

    /**
     * <b>후보는 새 이슈 ID 를 받는다 — 타입만이 아니라 값까지 가른다.</b>
     * 타입만 가르면 컴파일러는 막지만 문자열로 왕복하는 경계(HTTP · JSON)는 못 막는다.
     * 값이 안 겹치면 둘을 바꿔 넣어도 조회가 <b>빈다.</b>
     *
     * <p>승격 CTE 를 {@code select id, id as issue_id} 로 되돌리면 여기서만 빨개진다 —
     * 대각선을 레포에 남긴 이유와 같은 자리다.
     */
    @Test
    void 승격은_후보_ID_가_아니라_새_이슈_ID_를_만든다() {
        var o = orchestrator(glossary);
        var r = runOneRound(o);
        var ids = candidateIds(r);
        assertThat(ids).isNotEmpty();

        o.confirm(ids, List.of());

        for (var candidateId : ids) {
            var candidate = UUID.fromString(candidateId.value());
            var promoted = db.select(ISSUE_CANDIDATE.PROMOTED_ISSUE_ID).from(ISSUE_CANDIDATE)
                             .where(ISSUE_CANDIDATE.ID.eq(candidate))
                             .fetchOne(ISSUE_CANDIDATE.PROMOTED_ISSUE_ID);

            assertThat(promoted).as("확인됐는지와 어느 이슈가 됐는지를 한 컬럼이 답한다").isNotNull();
            assertThat(promoted).as("후보 ID 와 값이 겹치지 않는다").isNotEqualTo(candidate);
            assertThat(db.fetchExists(ISSUE, ISSUE.ID.eq(promoted))).isTrue();
            assertThat(db.fetchExists(ISSUE, ISSUE.ID.eq(candidate)))
                    .as("후보 ID 로는 이슈가 안 잡힌다").isFalse();
        }
    }

    /**
     * 재처리는 이전 벌을 지우지 않고 포인터를 옮긴다 — 지우면 나란히 볼 대상이 사라진다.
     * 그리고 미확인 후보는 현재 벌만 준다: 안 그러면 벌이 셋 병존할 때 같은 회의록에서 뽑은
     * 후보가 3배로 뜨고 다음 회차 컨텍스트에도 3배로 들어간다.
     */
    @Test
    void 추출이_벌로_쌓이고_현재_벌만_보인다() {
        var o = orchestrator(glossary);
        var meeting = meetings.newMeeting();
        o.transcribe(meeting, audio());

        var first = o.extract(meeting, "1");
        var second = o.extract(meeting, "2");
        var id = UUID.fromString(meeting.value());

        assertThat(first.extraction().id()).isNotEqualTo(second.extraction().id());
        assertThat(db.fetchCount(EXTRACTION, EXTRACTION.MEETING_ID.eq(id)))
                .as("두 벌이 병존한다").isEqualTo(2);
        assertThat(db.select(MEETING.CURRENT_EXTRACTION_ID).from(MEETING).where(MEETING.ID.eq(id))
                     .fetchOne(MEETING.CURRENT_EXTRACTION_ID))
                .as("현재 벌 포인터가 둘째로 옮겨졌다")
                .isEqualTo(UUID.fromString(second.extraction().id().value()));

        assertThat(extractions.unconfirmedCandidates(meeting))
                .as("후보가 두 배로 뜨지 않는다")
                .hasSize(second.extraction().issueCandidates().size());
    }
}
