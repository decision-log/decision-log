package dl.app;

import dl.adapters.extract.MarkerExtractAdapter;
import dl.adapters.stt.SimulatorSttAdapter;
import dl.adapters.stt.SimulatorSttAdapter.Mode;
import dl.adapters.stt.SimulatorSttAdapter.Rule;
import dl.app.store.QueryCounter;
import dl.domain.회차오케스트레이터;
import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Audio;
import dl.domain.ports.단위작업;
import dl.domain.ports.저장소.*;
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
class 걷는뼈대통합테스트 {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @TestConfiguration
    static class 카운터배선 {
        @Bean QueryCounter queryCounter() { return new QueryCounter(); }
        @Bean ExecuteListenerProvider 카운터(QueryCounter c) { return new DefaultExecuteListenerProvider(c); }
    }

    @Autowired 회의저장소 회의들;
    @Autowired 이슈저장소 이슈들;
    @Autowired 용어집저장소 용어집;
    @Autowired 단위작업 단위;
    @Autowired QueryCounter 카운터;
    @Value("${local.server.port}") int 포트;

    static final List<String> 대본 = List.of(
            "리버스 프록시는 «용어:Caddy»로 가죠 «이슈:리버스 프록시를 무엇으로 할 것인가»",
            "«용어:툴 콜링» 실패를 «이슈:툴 콜링 실패를 어떻게 처리할 것인가»",
            "«용어:스크럼»은 매일 아침에 합니다");
    static final List<Rule> 규칙 = List.of(new Rule("Caddy", Mode.일관, List.of("캐디")));

    private 회차오케스트레이터 오케(용어집저장소 g) {
        return new 회차오케스트레이터(new SimulatorSttAdapter(대본, 규칙, 7L),
                new MarkerExtractAdapter(), 회의들, 이슈들, g, 단위);
    }

    private HttpResponse<String> GET(String 경로) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + 포트 + 경로)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 서버가_뜨고_화면과_API가_응답한다() throws Exception {
        var api = GET("/api/health");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).contains("\"ok\":true");

        var 화면 = GET("/");
        assertThat(화면.statusCode()).isEqualTo(200);
        assertThat(화면.body()).contains("<div id=\"root\">");
    }

    @Test
    void 확인은_한_단위다() {
        var o = 오케(용어집);
        var r = o.돈다(new Audio(new byte[0], "m.mp3"), "1");
        int 이슈전 = 이슈들.전량().size(), 용어전 = 용어집.전량().size();

        var 터지는용어집 = new 용어집저장소() {
            public void 추가(List<용어> ts) { throw new IllegalStateException("DB 끊김"); }
            public List<용어> 전량() { return 용어집.전량(); }
            public void 수정(String 기존표기, String 새표기, String 새뜻) { 용어집.수정(기존표기, 새표기, 새뜻); }
        };

        Assertions.assertThrows(IllegalStateException.class, () ->
                오케(터지는용어집).확인(r.후보().stream().map(이슈후보::id).toList(), r.용어후보()));

        assertThat(이슈들.전량()).hasSize(이슈전);      // 승격이 되돌아갔다
        assertThat(용어집.전량()).hasSize(용어전);
    }

    @Test
    void 확인이_후보_수만큼_왕복하지_않는다() {
        var o = 오케(용어집);
        var r = o.돈다(new Audio(new byte[0], "m.mp3"), "1");

        카운터.초기화();
        o.확인(r.후보().stream().map(이슈후보::id).toList(), r.용어후보());

        // 승격 2방(insert…select + update) + 용어 upsert 1방. 후보 수와 무관해야 한다.
        assertThat(카운터.횟수())
                .as("확인() SQL: %s", 카운터.쿼리())
                .isLessThanOrEqualTo(4);
        assertThat(용어집.전량()).extracting(용어::표기).contains("툴 콜링", "스크럼");
    }
}
