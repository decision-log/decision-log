package dl.app;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static dl.app.jooq.Tables.JOB;
import static dl.app.jooq.Tables.MEETING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * 오디오를 올리면 잡이 돌고, 실패하면 다시 돌린다 — 화면부터 DB까지 HTTP 로 관통한다.
 *
 * 걷는뼈대통합테스트와 같은 배선으로 돈다. 잡은 스레드에서 돌므로 상태는 폴링으로 본다 —
 * 화면이 하는 일과 같다.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class 잡통합테스트 {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @TempDir static Path 데이터디렉토리;

    @DynamicPropertySource
    static void 프로퍼티(DynamicPropertyRegistry 등록) {
        등록.add("dl.data-dir", () -> 데이터디렉토리.toString());
        등록.add("dl.processing.step-millis", () -> "50");   // 5단계 × 50ms
    }

    @Autowired DSLContext db;
    @Value("${local.server.port}") int 포트;

    static final ObjectMapper 매퍼 = new ObjectMapper();

    // ── 케이스 ──────────────────────────────────────────────

    @Test
    void 회의_생성시_명단_전체가_참가자_기본값이다() throws Exception {
        보낸다("PUT", "/api/participants", "[\"가영\",\"나영\",\"다영\"]");
        assertThat(GET("/api/participants").body()).contains("가영").contains("나영").contains("다영");

        // participants 를 안 보내면 서버가 명단 전체를 스냅샷한다
        var 생성 = 보낸다("POST", "/api/meetings", "{\"title\":\"1회차\",\"heldOn\":\"2026-03-02\"}");
        assertThat(생성.statusCode()).isEqualTo(200);

        var 단건 = 매퍼.readTree(생성.body());
        assertThat(이름들(단건.get("participants"))).containsExactly("가영", "나영", "다영");
        assertThat(단건.get("title").asText()).isEqualTo("1회차");
        assertThat(단건.get("heldOn").asText()).isEqualTo("2026-03-02");
        assertThat(단건.get("audioUploaded").asBoolean()).isFalse();
        assertThat(단건.get("job").isNull()).isTrue();

        // 고른 이름만 보내면 그것만 복사된다
        var 고름 = 매퍼.readTree(보낸다("POST", "/api/meetings",
                "{\"title\":\"2회차\",\"heldOn\":\"2026-03-09\",\"participants\":[\"나영\"]}").body());
        assertThat(이름들(고름.get("participants"))).containsExactly("나영");

        // 명단이 나중에 바뀌어도 그 회의에 그 사람들이 있었다는 사실은 안 변한다
        보낸다("PUT", "/api/participants", "[\"라영\"]");
        var 다시읽음 = 매퍼.readTree(GET("/api/meetings/" + 단건.get("id").asText()).body());
        assertThat(이름들(다시읽음.get("participants"))).containsExactly("가영", "나영", "다영");
    }

    @Test
    void 업로드가_곧_처리_시작이고_완료에_도달한다() throws Exception {
        var 회의 = 회의만든다("완료될 회의");

        // 처리 시작을 따로 부르지 않는다 — 업로드가 곧 시작이다
        var 업로드 = 업로드한다(회의, "회의녹음.mp3", "소리".getBytes(StandardCharsets.UTF_8));
        assertThat(업로드.statusCode()).as("업로드 응답: %s", 업로드.body()).isEqualTo(200);
        assertThat(매퍼.readTree(업로드.body()).get("audioUploaded").asBoolean()).isTrue();

        var 잡 = 잡이도달할때까지(회의, "완료");
        assertThat(잡.get("progressDone").asInt()).isEqualTo(5);
        assertThat(잡.get("progressTotal").asInt()).isEqualTo(5);
        assertThat(잡.get("failureReason").isNull()).isTrue();

        // {데이터디렉토리}/{회의ID}/{원본파일명}
        assertThat(데이터디렉토리.resolve(회의).resolve("회의녹음.mp3")).exists();

        // 재업로드(교체)는 범위 밖 — 이미 있으면 거절한다
        assertThat(업로드한다(회의, "다른것.mp3", "소리".getBytes(StandardCharsets.UTF_8)).statusCode())
                .isEqualTo(409);
    }

    @Test
    void fail_파일명은_실패하고_재시도해도_다시_실패한다() throws Exception {
        var 회의 = 회의만든다("실패할 회의");
        assertThat(업로드한다(회의, "fail.mp3", "소리".getBytes(StandardCharsets.UTF_8)).statusCode())
                .isEqualTo(200);

        var 실패 = 잡이도달할때까지(회의, "실패");
        var 사유 = 실패.get("failureReason").asText();
        assertThat(사유).contains("가짜");                      // 진짜 실패로 오해되면 안 된다
        assertThat(실패.get("progressDone").asInt()).isEqualTo(2);

        // 재시도는 같은 행 리셋 — 진행률과 사유가 함께 초기화된다
        var 재시도 = 보낸다("POST", "/api/meetings/" + 회의 + "/retry", null);
        assertThat(재시도.statusCode()).as("재시도 응답: %s", 재시도.body()).isEqualTo(200);

        // 같은 파일이므로 다시 실패한다 — fail-once 같은 상태 있는 가짜는 만들지 않았다
        var 다시실패 = 잡이도달할때까지(회의, "실패");
        assertThat(다시실패.get("failureReason").asText()).isEqualTo(사유);

        // 목록에도 보인다
        assertThat(GET("/api/meetings").body()).contains("실패할 회의");
    }

    /**
     * 재시도는 실패한 행만 되돌린다 — 처리중인 잡을 리셋하면 워커가 둘이 된다.
     * 모델 검사에서 이 전이가 불변식 I1(같은 잡을 동시에 처리하는 태스크 ≤ 1)의 급소였다.
     */
    @Test
    void 처리중_잡은_재시도를_거절한다() throws Exception {
        var 회의 = UUID.randomUUID();
        db.insertInto(MEETING).columns(MEETING.ID, MEETING.TITLE, MEETING.HELD_ON)
          .values(회의, "처리중인 회의", LocalDate.of(2026, 3, 2)).execute();
        db.insertInto(JOB)
          .columns(JOB.ID, JOB.MEETING_ID, JOB.STATE, JOB.PROGRESS_DONE, JOB.PROGRESS_TOTAL)
          .values(UUID.randomUUID(), 회의, "처리중", 3, 5).execute();

        var 응답 = 보낸다("POST", "/api/meetings/" + 회의 + "/retry", null);
        assertThat(응답.statusCode()).as("재시도 응답: %s", 응답.body()).isEqualTo(409);

        // 거절이지 무해한 통과가 아니다 — 상태도 진행률도 그대로여야 한다
        var 잡 = 매퍼.readTree(GET("/api/meetings/" + 회의).body()).get("job");
        assertThat(잡.get("state").asText()).isEqualTo("처리중");
        assertThat(잡.get("progressDone").asInt()).isEqualTo(3);
    }

    // ── 헬퍼 ────────────────────────────────────────────────

    private String 회의만든다(String 제목) throws Exception {
        var 응답 = 보낸다("POST", "/api/meetings",
                "{\"title\":\"%s\",\"heldOn\":\"2026-03-02\",\"participants\":[]}".formatted(제목));
        assertThat(응답.statusCode()).isEqualTo(200);
        return 매퍼.readTree(응답.body()).get("id").asText();
    }

    /** 화면이 하는 일과 같다 — 잡이 대기중·처리중인 동안 상태를 다시 묻는다. */
    private JsonNode 잡이도달할때까지(String 회의, String 상태) throws Exception {
        var 마감 = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        String 마지막 = null;
        while (System.nanoTime() < 마감) {
            마지막 = GET("/api/meetings/" + 회의).body();
            var 잡 = 매퍼.readTree(마지막).get("job");
            if (잡 != null && !잡.isNull() && 상태.equals(잡.get("state").asText())) return 잡;
            Thread.sleep(50);
        }
        throw new AssertionError("잡이 '%s' 에 도달하지 않았다. 마지막으로 본 것: %s".formatted(상태, 마지막));
    }

    private HttpResponse<String> 업로드한다(String 회의, String 파일명, byte[] 내용) throws Exception {
        var 경계 = "----dl" + UUID.randomUUID();
        var 앞 = ("--" + 경계 + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + 파일명 + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var 뒤 = ("\r\n--" + 경계 + "--\r\n").getBytes(StandardCharsets.UTF_8);

        var 본문 = new java.io.ByteArrayOutputStream();
        본문.write(앞); 본문.write(내용); 본문.write(뒤);

        var 요청 = HttpRequest.newBuilder(URI.create("http://localhost:" + 포트 + "/api/meetings/" + 회의 + "/audio"))
                .header("Content-Type", "multipart/form-data; boundary=" + 경계)
                .POST(HttpRequest.BodyPublishers.ofByteArray(본문.toByteArray()));
        return HttpClient.newHttpClient().send(요청.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static java.util.List<String> 이름들(JsonNode 배열) {
        var 결과 = new java.util.ArrayList<String>();
        배열.forEach(n -> 결과.add(n.asText()));
        return 결과;
    }

    private HttpResponse<String> GET(String 경로) throws Exception {
        return 보낸다("GET", 경로, null);
    }

    private HttpResponse<String> 보낸다(String 메서드, String 경로, String 본문) throws Exception {
        var 요청 = HttpRequest.newBuilder(URI.create("http://localhost:" + 포트 + 경로))
                .header("Content-Type", "application/json")
                .method(메서드, 본문 == null ? HttpRequest.BodyPublishers.noBody()
                                          : HttpRequest.BodyPublishers.ofString(본문, StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(요청.build(), HttpResponse.BodyHandlers.ofString());
    }
}
