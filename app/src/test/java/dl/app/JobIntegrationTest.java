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
import static dl.app.jooq.Tables.TRANSCRIPT;
import static dl.app.jooq.Tables.UTTERANCE;
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
class JobIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @TempDir static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dl.data-dir", () -> dataDir.toString());
    }

    @Autowired DSLContext db;
    @Value("${local.server.port}") int port;

    static final ObjectMapper mapper = new ObjectMapper();

    // ── 케이스 ──────────────────────────────────────────────

    @Test
    void 회의_생성시_명단_전체가_참가자_기본값이다() throws Exception {
        send("PUT", "/api/participants", "[\"가영\",\"나영\",\"다영\"]");
        assertThat(GET("/api/participants").body()).contains("가영").contains("나영").contains("다영");

        // participants 를 안 보내면 서버가 명단 전체를 스냅샷한다
        var create = send("POST", "/api/meetings", "{\"title\":\"1회차\",\"heldOn\":\"2026-03-02\"}");
        assertThat(create.statusCode()).isEqualTo(200);

        var detail = mapper.readTree(create.body());
        assertThat(names(detail.get("participants"))).containsExactly("가영", "나영", "다영");
        assertThat(detail.get("title").asText()).isEqualTo("1회차");
        assertThat(detail.get("heldOn").asText()).isEqualTo("2026-03-02");
        assertThat(detail.get("audioUploaded").asBoolean()).isFalse();
        assertThat(detail.get("job").isNull()).isTrue();

        // 고른 이름만 보내면 그것만 복사된다
        var picked = mapper.readTree(send("POST", "/api/meetings",
                "{\"title\":\"2회차\",\"heldOn\":\"2026-03-09\",\"participants\":[\"나영\"]}").body());
        assertThat(names(picked.get("participants"))).containsExactly("나영");

        // 명단이 나중에 바뀌어도 그 회의에 그 사람들이 있었다는 사실은 안 변한다
        send("PUT", "/api/participants", "[\"라영\"]");
        var reread = mapper.readTree(GET("/api/meetings/" + detail.get("id").asText()).body());
        assertThat(names(reread.get("participants"))).containsExactly("가영", "나영", "다영");
    }

    @Test
    void 업로드가_곧_처리_시작이고_결과가_회의록으로_저장된다() throws Exception {
        var meeting = createMeeting("완료될 회의");

        // 시뮬레이터가 꽂혀 있으므로 올리는 파일이 곧 대본이다 (ADR 0005)
        var script = ".gitignore 에 무엇을 넣을지 정합시다\n.git 을 지우면 안 됩니다";

        // 처리 시작을 따로 부르지 않는다 — 업로드가 곧 시작이다
        var upload = upload(meeting, "1회차대본.txt", script.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.statusCode()).as("업로드 응답: %s", upload.body()).isEqualTo(200);
        assertThat(mapper.readTree(upload.body()).get("audioUploaded").asBoolean()).isTrue();

        var job = waitForJob(meeting, "완료");
        assertThat(job.get("progressDone").asInt()).isEqualTo(1);   // 지금은 전사 한 단계뿐이다
        assertThat(job.get("progressTotal").asInt()).isEqualTo(1);
        assertThat(job.get("failureReason").isNull()).isTrue();

        // 회의 하나에 회의록 한 벌 — 스키마는 여러 벌을 받지만 화면은 1:1 로만 쓴다
        var transcript = theOnlyTranscript(meeting);
        var utterances = db.select(UTTERANCE.TEXT).from(UTTERANCE)
                     .where(UTTERANCE.TRANSCRIPT_ID.eq(transcript))
                     .orderBy(UTTERANCE.SEQ).fetch(r -> r.get(UTTERANCE.TEXT));
        assertThat(utterances)
                .as("용어집이 비었으므로 실측 규칙대로 깨진다")
                .containsExactly("GD 근원 에 무엇을 넣을지 정합시다", "점기 을 지우면 안 됩니다");

        // 화자 분리를 하지 않는 구현체가 거짓 라벨을 채우지 않는다
        assertThat(db.select(UTTERANCE.SPEAKER).from(UTTERANCE)
                     .where(UTTERANCE.TRANSCRIPT_ID.eq(transcript))
                     .fetch(r -> r.get(UTTERANCE.SPEAKER)))
                .containsOnlyNulls();

        // {데이터디렉토리}/{회의ID}/{원본파일명}
        assertThat(dataDir.resolve(meeting).resolve("1회차대본.txt")).exists();

        // 재업로드(교체)는 범위 밖 — 이미 있으면 거절한다
        assertThat(upload(meeting, "다른것.txt", script.getBytes(StandardCharsets.UTF_8)).statusCode())
                .isEqualTo(409);
    }

    /**
     * 사슬의 핵심 주장이 경계를 넘는지 — 용어집이 STT 컨텍스트로 나가면 그 용어가 안 깨진다.
     * 도메인이 우선순위 순 전량을 넘기고 어댑터가 자기 한도까지 채운다 (seams.md ⓵).
     */
    @Test
    void 용어집이_컨텍스트로_넘어가_그_용어는_안_깨진다() throws Exception {
        var script = "스크럼은 매일 아침에 합니다".getBytes(StandardCharsets.UTF_8);

        var firstRound = createMeeting("용어집 없는 회차");
        upload(firstRound, "1회차대본.txt", script);
        waitForJob(firstRound, "완료");
        assertThat(transcriptText(firstRound))
                .as("용어집이 비면 고착된다")
                .containsExactly("시끄러움은 매일 아침에 합니다");

        // 확인 화면에서 사람이 용어집을 채운다 (여기서는 붙여넣기로 대신한다)
        send("POST", "/api/glossary/paste", "{\"text\":\"스크럼\"}");

        var secondRound = createMeeting("용어집 있는 회차");
        upload(secondRound, "2회차대본.txt", script);
        waitForJob(secondRound, "완료");
        assertThat(transcriptText(secondRound))
                .as("같은 오디오 · 같은 시드인데 컨텍스트만 달라져 회의록이 바뀐다")
                .containsExactly("스크럼은 매일 아침에 합니다");
    }

    @Test
    void 텍스트가_아닌_파일은_실패하고_재시도해도_다시_실패한다() throws Exception {
        var meeting = createMeeting("실패할 회의");

        // 시뮬레이터는 대본을 받는다 — 진짜 오디오를 주면 쓰레기 회의록 대신 실패가 나온다
        var audioLikeBytes = new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0x80, 0x41};
        assertThat(upload(meeting, "회의녹음.mp3", audioLikeBytes).statusCode()).isEqualTo(200);

        var failure = waitForJob(meeting, "실패");
        var reasonOf = failure.get("failureReason").asText();
        assertThat(reasonOf).contains("대본").contains("회의녹음.mp3");   // 왜 실패했는지가 화면에서 읽힌다
        assertThat(failure.get("progressDone").asInt()).isZero();

        // 재시도는 같은 행 리셋 — 진행률과 사유가 함께 초기화된다
        var retry = send("POST", "/api/meetings/" + meeting + "/retry", null);
        assertThat(retry.statusCode()).as("재시도 응답: %s", retry.body()).isEqualTo(200);

        // 같은 파일이므로 다시 실패한다 — 재시도는 같은 오디오를 다시 돌리는 것뿐이다
        var failedAgain = waitForJob(meeting, "실패");
        assertThat(failedAgain.get("failureReason").asText()).isEqualTo(reasonOf);

        // 목록에도 보인다
        assertThat(GET("/api/meetings").body()).contains("실패할 회의");
    }

    /**
     * 재시도는 실패한 행만 되돌린다 — 처리중인 잡을 리셋하면 워커가 둘이 된다.
     * 모델 검사에서 이 전이가 불변식 I1(같은 잡을 동시에 처리하는 태스크 ≤ 1)의 급소였다.
     */
    @Test
    void 처리중_잡은_재시도를_거절한다() throws Exception {
        var meeting = UUID.randomUUID();
        db.insertInto(MEETING).columns(MEETING.ID, MEETING.TITLE, MEETING.HELD_ON)
          .values(meeting, "처리중인 회의", LocalDate.of(2026, 3, 2)).execute();
        db.insertInto(JOB)
          .columns(JOB.ID, JOB.MEETING_ID, JOB.STATE, JOB.PROGRESS_DONE, JOB.PROGRESS_TOTAL)
          .values(UUID.randomUUID(), meeting, "처리중", 3, 5).execute();

        var response = send("POST", "/api/meetings/" + meeting + "/retry", null);
        assertThat(response.statusCode()).as("재시도 응답: %s", response.body()).isEqualTo(409);

        // 거절이지 무해한 통과가 아니다 — 상태도 진행률도 그대로여야 한다
        var job = mapper.readTree(GET("/api/meetings/" + meeting).body()).get("job");
        assertThat(job.get("state").asText()).isEqualTo("처리중");
        assertThat(job.get("progressDone").asInt()).isEqualTo(3);
    }

    // ── 헬퍼 ────────────────────────────────────────────────

    private String createMeeting(String title) throws Exception {
        var response = send("POST", "/api/meetings",
                "{\"title\":\"%s\",\"heldOn\":\"2026-03-02\",\"participants\":[]}".formatted(title));
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body()).get("id").asText();
    }

    private java.util.List<String> transcriptText(String meeting) {
        return db.select(UTTERANCE.TEXT).from(UTTERANCE)
                 .where(UTTERANCE.TRANSCRIPT_ID.eq(theOnlyTranscript(meeting)))
                 .orderBy(UTTERANCE.SEQ).fetch(r -> r.get(UTTERANCE.TEXT));
    }

    /** 회의 하나에 회의록 한 벌이 있다는 것까지 확인하고 그 id 를 준다. */
    private UUID theOnlyTranscript(String meeting) {
        var rows = db.select(TRANSCRIPT.ID, TRANSCRIPT.SEQ).from(TRANSCRIPT)
                     .where(TRANSCRIPT.MEETING_ID.eq(UUID.fromString(meeting)))
                     .fetch();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get(TRANSCRIPT.SEQ)).isZero();
        return rows.getFirst().get(TRANSCRIPT.ID);
    }

    /** 화면이 하는 일과 같다 — 잡이 대기중·처리중인 동안 상태를 다시 묻는다. */
    private JsonNode waitForJob(String meeting, String state) throws Exception {
        var deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        String last = null;
        while (System.nanoTime() < deadline) {
            last = GET("/api/meetings/" + meeting).body();
            var job = mapper.readTree(last).get("job");
            if (job != null && !job.isNull() && state.equals(job.get("state").asText())) return job;
            Thread.sleep(50);
        }
        throw new AssertionError("잡이 '%s' 에 도달하지 않았다. 마지막으로 본 것: %s".formatted(state, last));
    }

    private HttpResponse<String> upload(String meeting, String filename, byte[] content) throws Exception {
        var boundary = "----dl" + UUID.randomUUID();
        var head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        var tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        var body = new java.io.ByteArrayOutputStream();
        body.write(head); body.write(content); body.write(tail);

        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/meetings/" + meeting + "/audio"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static java.util.List<String> names(JsonNode 배열) {
        var result = new java.util.ArrayList<String>();
        배열.forEach(n -> result.add(n.asText()));
        return result;
    }

    private HttpResponse<String> GET(String path) throws Exception {
        return send("GET", path, null);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                          : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
