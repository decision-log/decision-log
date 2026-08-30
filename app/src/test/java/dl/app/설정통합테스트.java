package dl.app;

import dl.domain.model.Model.용어;
import dl.domain.ports.저장소.*;
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
class 설정통합테스트 {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired 명단저장소 명단들;
    @Autowired 용어집저장소 용어집;
    @Value("${local.server.port}") int 포트;

    /** 표기는 primary key 라 케이스가 서로 안 밟도록 접두사를 붙인다 */
    private static String 고유(String 표기) { return 표기 + "-" + UUID.randomUUID(); }

    // ── 포트 ────────────────────────────────────────────────

    @Test
    void 명단은_통째로_교체된다() {
        명단들.명단저장(List.of("가영", "나영", "다영"));
        assertThat(명단들.명단()).containsExactly("가영", "나영", "다영");   // 이름순

        명단들.명단저장(List.of("가영", "라영"));
        assertThat(명단들.명단()).containsExactly("가영", "라영");           // 나영·다영은 사라졌다

        명단들.명단저장(List.of());
        assertThat(명단들.명단()).isEmpty();
    }

    @Test
    void 용어_추가는_이미_있는_표기를_무시한다() {
        var X = 고유("무시-X");
        var Y = 고유("무시-Y");

        용어집.추가(List.of(new 용어(X, "처음 뜻")));
        용어집.추가(List.of(new 용어(X, "나중 뜻"), new 용어(Y, null)));

        assertThat(용어집.전량()).contains(new 용어(X, "처음 뜻"), new 용어(Y, null));
    }

    @Test
    void 수정은_제자리_교체다() {
        var A = 고유("제자리-A");
        var B = 고유("제자리-B");
        용어집.추가(List.of(new 용어(A, "옛 뜻")));
        int 전 = 용어집.전량().size();

        용어집.수정(A, B, "새 뜻");

        assertThat(용어집.전량()).hasSize(전);
        assertThat(용어집.전량()).extracting(용어::표기).doesNotContain(A);
        assertThat(용어집.전량()).contains(new 용어(B, "새 뜻"));
    }

    @Test
    void 수정은_표기_충돌을_거부한다() {
        var A = 고유("충돌-A");
        var B = 고유("충돌-B");
        용어집.추가(List.of(new 용어(A, "가"), new 용어(B, "나")));
        var 전 = 용어집.전량();

        Assertions.assertThrows(표기충돌.class, () -> 용어집.수정(A, B, "다"));

        assertThat(용어집.전량()).containsExactlyInAnyOrderElementsOf(전);
    }

    @Test
    void 없는_표기는_수정할_수_없다() {
        Assertions.assertThrows(java.util.NoSuchElementException.class,
                () -> 용어집.수정(고유("없는"), 고유("새것"), "뜻"));
    }

    @Test
    void 뜻만_고칠_때는_충돌이_아니다() {
        var A = 고유("뜻만-A");
        용어집.추가(List.of(new 용어(A, "옛 뜻")));

        용어집.수정(A, A, "고친 뜻");
        assertThat(용어집.전량()).contains(new 용어(A, "고친 뜻"));

        용어집.수정(A, A, null);                       // 뜻 비우기
        assertThat(용어집.전량()).contains(new 용어(A, null));
    }

    // ── HTTP 관통 ───────────────────────────────────────────

    @Test
    void 설정_API가_화면부터_DB까지_관통한다() throws Exception {
        // 명단: 통째 교체가 GET 에 그대로 비친다
        assertThat(보낸다("PUT", "/api/participants", "[\"가영\",\"나영\"]").statusCode()).isEqualTo(200);
        assertThat(GET("/api/participants").body()).contains("가영").contains("나영");

        assertThat(보낸다("PUT", "/api/participants", "[\"가영\"]").statusCode()).isEqualTo(200);
        assertThat(GET("/api/participants").body()).doesNotContain("나영");

        // 중복 이름은 거부
        assertThat(보낸다("PUT", "/api/participants", "[\"가영\",\"가영\"]").statusCode()).isEqualTo(400);

        // 붙여넣기: 탭 · 콜론 · 구분자 없음 · 같은 텍스트 안의 중복
        var 표기A = 고유("관통-A");
        var 표기B = 고유("관통-B");
        var 표기C = 고유("관통-C");
        var 텍스트 = 표기A + "\t탭으로 가른 뜻\n"
                + 표기B + ": 콜론으로 가른 뜻\n"
                + 표기C + "\n"
                + 표기A + ": 같은 붙여넣기 안의 중복\n";
        var 넣기 = 보낸다("POST", "/api/glossary/paste", 본문(텍스트));
        assertThat(넣기.statusCode()).isEqualTo(200);
        assertThat(넣기.body()).contains("\"added\":3").contains("\"ignored\":0");

        var 전량 = GET("/api/glossary").body();
        assertThat(전량).contains("탭으로 가른 뜻").contains("콜론으로 가른 뜻").contains(표기C);
        assertThat(전량).contains("\"표기\":").contains("\"뜻\":");   // 화면이 읽는 키 이름

        // 두 번 붙여넣어도 결과가 같다 — 전부 이미 있어 무시된다
        var 두번째 = 보낸다("POST", "/api/glossary/paste", 본문(텍스트));
        assertThat(두번째.body()).contains("\"added\":0").contains("\"ignored\":3");

        // 수정: 표기 충돌은 409
        var 충돌 = 보낸다("PUT", "/api/glossary/entry",
                "{\"기존표기\":\"%s\",\"새표기\":\"%s\",\"새뜻\":null}".formatted(표기A, 표기B));
        assertThat(충돌.statusCode()).isEqualTo(409);

        // 수정: 없는 표기는 404
        var 없음 = 보낸다("PUT", "/api/glossary/entry",
                "{\"기존표기\":\"%s\",\"새표기\":\"%s\",\"새뜻\":null}".formatted(고유("없는"), 고유("새것")));
        assertThat(없음.statusCode()).isEqualTo(404);

        // 수정: 제자리 교체가 GET 에 비친다
        var 새표기 = 고유("관통-A-고침");
        var 고침 = 보낸다("PUT", "/api/glossary/entry",
                "{\"기존표기\":\"%s\",\"새표기\":\"%s\",\"새뜻\":\"고친 뜻\"}".formatted(표기A, 새표기));
        assertThat(고침.statusCode()).isEqualTo(200);
        assertThat(GET("/api/glossary").body()).contains(새표기).doesNotContain(표기A);
    }

    // ── HTTP 헬퍼 ───────────────────────────────────────────

    private static String 본문(String 텍스트) {
        return "{\"text\":\"%s\"}".formatted(텍스트.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\t", "\\t").replace("\n", "\\n"));
    }

    private HttpResponse<String> GET(String 경로) throws Exception {
        return 보낸다("GET", 경로, null);
    }

    private HttpResponse<String> 보낸다(String 메서드, String 경로, String 본문) throws Exception {
        var 요청 = HttpRequest.newBuilder(URI.create("http://localhost:" + 포트 + 경로))
                .header("Content-Type", "application/json")
                .method(메서드, 본문 == null ? HttpRequest.BodyPublishers.noBody()
                                          : HttpRequest.BodyPublishers.ofString(본문));
        return HttpClient.newHttpClient().send(요청.build(), HttpResponse.BodyHandlers.ofString());
    }
}
