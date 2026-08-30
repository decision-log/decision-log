package dl.app;

import dl.app.job.JooqJobs;
import dl.app.job.잡실행기;
import dl.app.store.JooqMeetings;
import dl.domain.ports.단위작업;
import dl.domain.ports.저장소.명단저장소;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 회의 — 생성 · 목록 · 단건 · 오디오 업로드 · 재시도.
 *
 * 참가자는 생성 시 명단을 복사한 스냅샷이다. 목록을 안 보내면(null) 서버가 명단 전체를 찍는다 —
 * 화면이 "전체 선택된 기본값"으로 보여주는 것과 같은 기본값을 서버도 갖는다.
 *
 * 업로드가 곧 처리 시작이다. 별도의 "시작" 호출은 없다.
 */
@RestController
@RequestMapping("/api/meetings")
public class MeetingController {
    private final JooqMeetings 회의들;
    private final JooqJobs 잡들;
    private final 잡실행기 실행기;
    private final 명단저장소 명단들;
    private final 단위작업 단위;
    private final Path 데이터디렉토리;

    public MeetingController(JooqMeetings 회의들, JooqJobs 잡들, 잡실행기 실행기,
                             명단저장소 명단들, 단위작업 단위,
                             @Value("${dl.data-dir}") String 데이터디렉토리) {
        this.회의들 = 회의들;
        this.잡들 = 잡들;
        this.실행기 = 실행기;
        this.명단들 = 명단들;
        this.단위 = 단위;
        this.데이터디렉토리 = Path.of(데이터디렉토리);
    }

    /** participants 가 없으면(null) 명단 전체가 기본값이다 — 빈 배열은 "아무도 없음"이라 구분한다. */
    public record 생성요청(String title, LocalDate heldOn, List<String> participants) {}

    /** 상태 값은 한글, JSON 키는 영문 — issue_candidate.state 관례를 따른다. */
    public record 잡응답(String state, int progressDone, int progressTotal, String failureReason) {}

    /** job 은 오디오를 올리기 전엔 null 이다 — 업로드 하나가 잡 하나다. */
    public record 단건응답(String id, String title, LocalDate heldOn, List<String> participants,
                          boolean audioUploaded, 잡응답 job) {}

    public record 줄응답(String id, String title, LocalDate heldOn) {}

    // ── 회의 ────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> 생성(@RequestBody 생성요청 요청) {
        var 제목 = 요청.title() == null ? "" : 요청.title().trim();
        if (제목.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "제목을 입력하세요"));
        if (요청.heldOn() == null) return ResponseEntity.badRequest().body(Map.of("error", "회의 날짜를 고르세요"));

        var id = 단위.안에서(() -> {
            var 참가자 = 요청.participants() == null ? 명단들.명단() : 요청.participants();
            return 회의들.생성(제목, 요청.heldOn(), 참가자);
        });
        return 다시읽어서(id);
    }

    @GetMapping
    public List<줄응답> 목록() {
        return 회의들.목록().stream()
                .map(m -> new 줄응답(m.id().toString(), m.제목(), m.날짜()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> 단건(@PathVariable String id) {
        return 찾는다(id).<ResponseEntity<?>>map(m -> ResponseEntity.ok(단건으로(m))).orElseGet(MeetingController::없음);
    }

    // ── 오디오 ──────────────────────────────────────────────

    /** 업로드가 곧 처리 시작이다. 재업로드(교체)는 범위 밖 — 실패는 재시도로 푼다. */
    @PostMapping("/{id}/audio")
    public ResponseEntity<?> 오디오업로드(@PathVariable String id,
                                        @RequestParam("file") MultipartFile 파일) {
        var 있는것 = 찾는다(id);
        if (있는것.isEmpty()) return 없음();
        if (있는것.get().오디오경로() != null) return 이미올림();   // 싼 거절 — 진짜 판정은 아래 조건부 UPDATE

        var 회의 = 있는것.get().id();
        var 자리 = 데이터디렉토리.resolve(회의.toString());
        var 저장경로 = 자리.resolve(정제된파일명(파일.getOriginalFilename()));
        var 잡 = UUID.randomUUID();

        boolean 선점 = 단위.안에서(() -> {
            // 선점이 파일 쓰기보다 **먼저** 온다. 뒤집으면 경쟁에서 진 쪽이 이미 디스크에 쓴 뒤라,
            // 같은 파일명이면 이긴 쪽 오디오를 덮어쓴 채 409 를 돌려준다(워커가 그 파일을 읽는 중일 수 있다).
            if (!회의들.오디오경로기록(회의, 저장경로.toString())) return false;
            잡들.생성(잡, 회의);
            // 쓰기가 트랜잭션 안에 있으므로 실패하면 선점도 잡도 없던 일이 된다 — 대기중인 유령이 안 남는다.
            // 멀티파트는 이미 임시파일에 받아둔 뒤라 여기서는 로컬 복사다(네트워크를 붙들지 않는다).
            저장한다(파일, 자리, 저장경로);
            return true;
        });
        if (!선점) return 이미올림();

        실행기.제출(잡, 회의, 저장경로);   // 커밋 뒤에 제출한다 — 커밋 전이면 워커가 그 행을 못 본다
        return 다시읽어서(회의);
    }

    /** 재시도는 같은 행 리셋이다. 처리중·완료인 잡은 거절한다. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> 재시도(@PathVariable String id) {
        var 있는것 = 찾는다(id);
        if (있는것.isEmpty()) return 없음();

        var 회의 = 있는것.get().id();
        var 잡 = 잡들.회의의잡(회의);
        if (잡.isEmpty()) return 없음();
        if (!단위.안에서(() -> 잡들.재시도리셋(잡.get().id())))
            return ResponseEntity.status(409).body(Map.of("error", "처리중이거나 완료된 잡은 재시도할 수 없다"));

        실행기.제출(잡.get().id(), 회의, Path.of(있는것.get().오디오경로()));
        return 다시읽어서(회의);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 회의 하나를 집는다. 잘못된 형식의 id 는 없는 회의와 같은 취급이다 —
     * 화면엔 둘 다 "없다"로 보인다.
     */
    private Optional<JooqMeetings.단건> 찾는다(String id) {
        UUID 회의;
        try { 회의 = UUID.fromString(id); } catch (IllegalArgumentException e) { return Optional.empty(); }
        return 회의들.단건(회의);
    }

    /** 바꾼 뒤엔 다시 읽어서 준다 — 오디오·잡이 방금 생겼으니 집어온 것은 이미 낡았다. */
    private ResponseEntity<?> 다시읽어서(UUID 회의) {
        var m = 회의들.단건(회의);
        return m.isEmpty() ? 없음() : ResponseEntity.ok(단건으로(m.get()));
    }

    private 단건응답 단건으로(JooqMeetings.단건 m) {
        return new 단건응답(m.id().toString(), m.제목(), m.날짜(), m.참가자(),
                            m.오디오경로() != null,
                            잡들.회의의잡(m.id())
                               .map(j -> new 잡응답(j.상태(), j.완료(), j.전체(), j.실패사유()))
                               .orElse(null));
    }

    /**
     * 트랜잭션 안에서 부른다 — 던지면 선점이 롤백된다.
     * 검사 예외를 못 던지는 자리(단위작업은 Supplier 를 받는다)라 감싼다.
     */
    private static void 저장한다(MultipartFile 파일, Path 자리, Path 저장경로) {
        try {
            Files.createDirectories(자리);
            파일.transferTo(저장경로);
        } catch (IOException e) {
            throw new UncheckedIOException("오디오를 저장하지 못했다: " + 저장경로, e);
        }
    }

    /**
     * 원본 파일명에서 경로 성분을 떼어낸다 — 업로드한 이름이 디렉토리를 벗어나면 안 된다.
     * 이름이 남지 않으면 audio 로 둔다.
     */
    static String 정제된파일명(String 원본) {
        if (원본 == null || 원본.isBlank()) return "audio";
        var 이름 = Path.of(원본.trim()).getFileName();
        if (이름 == null) return "audio";
        var 문자열 = 이름.toString();
        return 문자열.isBlank() || 문자열.equals(".") || 문자열.equals("..") ? "audio" : 문자열;
    }

    private static ResponseEntity<?> 없음() {
        return ResponseEntity.status(404).body(Map.of("error", "없는 회의입니다"));
    }

    private static ResponseEntity<?> 이미올림() {
        return ResponseEntity.status(409).body(Map.of("error", "이미 오디오를 올린 회의입니다"));
    }
}
