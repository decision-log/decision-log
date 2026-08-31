package dl.app;

import dl.domain.model.Model.Term;
import dl.domain.ports.Stores.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * 설정(명단 · 용어집)이 화면부터 DB까지 관통하는지.
 *
 * 걷는뼈대통합테스트와 같은 배선으로 돈다 — 저장소 계약은 포트로, 관통은 HTTP 로 본다.
 * glossary 는 표기가 열쇠라 케이스마다 고유한 표기를 쓴다(테스트끼리 안 밟게).
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class SettingsIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired RosterStore roster;
    @Autowired GlossaryStore glossary;
    @Value("${local.server.port}") int port;

    /** 표기는 primary key 라 케이스가 서로 안 밟도록 접두사를 붙인다 */
    private static String unique(String spelling) { return spelling + "-" + UUID.randomUUID(); }

    // ── 포트 ────────────────────────────────────────────────

    @Test
    void 명단은_통째로_교체된다() {
        roster.saveRoster(List.of("가영", "나영", "다영"));
        assertThat(roster.roster()).containsExactly("가영", "나영", "다영");   // 이름순

        roster.saveRoster(List.of("가영", "라영"));
        assertThat(roster.roster()).containsExactly("가영", "라영");           // 나영·다영은 사라졌다

        roster.saveRoster(List.of());
        assertThat(roster.roster()).isEmpty();
    }

    @Test
    void 용어_추가는_이미_있는_표기를_무시한다() {
        var X = unique("무시-X");
        var Y = unique("무시-Y");

        glossary.add(List.of(new Term(X, "처음 뜻")));
        glossary.add(List.of(new Term(X, "나중 뜻"), new Term(Y, null)));

        assertThat(glossary.all()).contains(new Term(X, "처음 뜻"), new Term(Y, null));
    }

    @Test
    void 수정은_제자리_교체다() {
        var A = unique("제자리-A");
        var B = unique("제자리-B");
        glossary.add(List.of(new Term(A, "옛 뜻")));
        int before = glossary.all().size();

        glossary.edit(A, B, "새 뜻");

        assertThat(glossary.all()).hasSize(before);
        assertThat(glossary.all()).extracting(Term::spelling).doesNotContain(A);
        assertThat(glossary.all()).contains(new Term(B, "새 뜻"));
    }

    @Test
    void 수정은_표기_충돌을_거부한다() {
        var A = unique("충돌-A");
        var B = unique("충돌-B");
        glossary.add(List.of(new Term(A, "가"), new Term(B, "나")));
        var before = glossary.all();

        Assertions.assertThrows(SpellingConflict.class, () -> glossary.edit(A, B, "다"));

        assertThat(glossary.all()).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void 없는_표기는_수정할_수_없다() {
        Assertions.assertThrows(java.util.NoSuchElementException.class,
                () -> glossary.edit(unique("없는"), unique("새것"), "뜻"));
    }

    @Test
    void 뜻만_고칠_때는_충돌이_아니다() {
        var A = unique("뜻만-A");
        glossary.add(List.of(new Term(A, "옛 뜻")));

        glossary.edit(A, A, "고친 뜻");
        assertThat(glossary.all()).contains(new Term(A, "고친 뜻"));

        glossary.edit(A, A, null);                       // 뜻 비우기
        assertThat(glossary.all()).contains(new Term(A, null));
    }

    // ── HTTP 관통 ───────────────────────────────────────────

    @Test
    void 설정_API가_화면부터_DB까지_관통한다() throws Exception {
        // 명단: 통째 교체가 GET 에 그대로 비친다
        assertThat(send("PUT", "/api/participants", "[\"가영\",\"나영\"]").statusCode()).isEqualTo(200);
        assertThat(GET("/api/participants").body()).contains("가영").contains("나영");

        assertThat(send("PUT", "/api/participants", "[\"가영\"]").statusCode()).isEqualTo(200);
        assertThat(GET("/api/participants").body()).doesNotContain("나영");

        // 중복 이름은 거부
        assertThat(send("PUT", "/api/participants", "[\"가영\",\"가영\"]").statusCode()).isEqualTo(400);

        // 붙여넣기: 탭 · 콜론 · 구분자 없음 · 같은 텍스트 안의 중복
        var spellingA = unique("관통-A");
        var spellingB = unique("관통-B");
        var spellingC = unique("관통-C");
        var text = spellingA + "\t탭으로 가른 뜻\n"
                + spellingB + ": 콜론으로 가른 뜻\n"
                + spellingC + "\n"
                + spellingA + ": 같은 붙여넣기 안의 중복\n";
        var paste = send("POST", "/api/glossary/paste", body(text));
        assertThat(paste.statusCode()).isEqualTo(200);
        assertThat(paste.body()).contains("\"added\":3").contains("\"ignored\":0");

        var all = GET("/api/glossary").body();
        assertThat(all).contains("탭으로 가른 뜻").contains("콜론으로 가른 뜻").contains(spellingC);
        assertThat(all).contains("\"spelling\":").contains("\"meaning\":");   // 화면이 읽는 키 이름

        // 두 번 붙여넣어도 결과가 같다 — 전부 이미 있어 무시된다
        var second = send("POST", "/api/glossary/paste", body(text));
        assertThat(second.body()).contains("\"added\":0").contains("\"ignored\":3");

        // 수정: 표기 충돌은 409
        var conflict = send("PUT", "/api/glossary/entry",
                "{\"oldSpelling\":\"%s\",\"newSpelling\":\"%s\",\"newMeaning\":null}".formatted(spellingA, spellingB));
        assertThat(conflict.statusCode()).isEqualTo(409);

        // 수정: 없는 표기는 404
        var notFound = send("PUT", "/api/glossary/entry",
                "{\"oldSpelling\":\"%s\",\"newSpelling\":\"%s\",\"newMeaning\":null}".formatted(unique("없는"), unique("새것")));
        assertThat(notFound.statusCode()).isEqualTo(404);

        // 수정: 제자리 교체가 GET 에 비친다
        var newSpelling = unique("관통-A-고침");
        var fixed = send("PUT", "/api/glossary/entry",
                "{\"oldSpelling\":\"%s\",\"newSpelling\":\"%s\",\"newMeaning\":\"고친 뜻\"}".formatted(spellingA, newSpelling));
        assertThat(fixed.statusCode()).isEqualTo(200);
        assertThat(GET("/api/glossary").body()).contains(newSpelling).doesNotContain(spellingA);
    }

    // ── HTTP 헬퍼 ───────────────────────────────────────────

    private static String body(String text) {
        return "{\"text\":\"%s\"}".formatted(text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\t", "\\t").replace("\n", "\\n"));
    }

    private HttpResponse<String> GET(String path) throws Exception {
        return send("GET", path, null);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                          : HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
