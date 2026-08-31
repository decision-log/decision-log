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
    @Autowired IssueStore issues;
    @Autowired GlossaryStore glossary;
    @Autowired UnitOfWork unit;
    @Autowired QueryCounter counter;
    @Value("${local.server.port}") int port;

    /** 가짜 STT 는 오디오가 아니라 대본을 받는다 (ADR 0005) — 그 자리가 Audio 다. */
    static final String script = String.join("\n", List.of(
            "리버스 프록시는 «용어:Caddy»로 가죠 «이슈:리버스 프록시를 무엇으로 할 것인가»",
            "«용어:툴 콜링» 실패를 «이슈:툴 콜링 실패를 어떻게 처리할 것인가»",
            "«용어:스크럼»은 매일 아침에 합니다"));

    /** 이 테스트가 재는 것은 오염이 아니라 배선이라, 규칙은 한 벌만 둔다. */
    static final List<Rule> rules = List.of(new Rule("Caddy", Mode.STEADY, List.of("캐디")));

    private RoundOrchestrator orchestrator(GlossaryStore g) {
        return new RoundOrchestrator(new SimulatorSttAdapter(rules, 7L),
                new MarkerExtractAdapter(), meetings, issues, g, unit);
    }

    /** 회의를 만드는 것은 호출자의 일이다 — 오케스트레이터가 더 이상 안 만든다. */
    private RoundOrchestrator.RoundResult runOneRound(RoundOrchestrator o) {
        return o.run(meetings.newMeeting(),
                new Audio(script.getBytes(java.nio.charset.StandardCharsets.UTF_8), "m.txt"), "1");
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
                orchestrator(explodingGlossary).confirm(r.candidates().stream().map(IssueCandidate::id).toList(), r.termCandidates()));

        assertThat(issues.all()).hasSize(issuesBefore);      // 승격이 되돌아갔다
        assertThat(glossary.all()).hasSize(termsBefore);
    }

    @Test
    void 확인이_후보_수만큼_왕복하지_않는다() {
        var o = orchestrator(glossary);
        var r = runOneRound(o);

        counter.reset();
        o.confirm(r.candidates().stream().map(IssueCandidate::id).toList(), r.termCandidates());

        // 승격 2방(insert…select + update) + 용어 upsert 1방. 후보 수와 무관해야 한다.
        assertThat(counter.count())
                .as("확인() SQL: %s", counter.queries())
                .isLessThanOrEqualTo(4);
        assertThat(glossary.all()).extracting(Term::spelling).contains("툴 콜링", "스크럼");
    }
}
