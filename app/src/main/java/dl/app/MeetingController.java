package dl.app;

import dl.app.job.JooqJobs;
import dl.app.job.JobRunner;
import dl.app.store.JooqMeetings;
import dl.domain.ports.UnitOfWork;
import dl.domain.ports.Stores.RosterStore;
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
    private final JooqMeetings meetings;
    private final JooqJobs jobs;
    private final JobRunner runner;
    private final RosterStore roster;
    private final UnitOfWork unit;
    private final Path dataDir;

    public MeetingController(JooqMeetings meetings, JooqJobs jobs, JobRunner runner,
                             RosterStore roster, UnitOfWork unit,
                             @Value("${dl.data-dir}") String dataDir) {
        this.meetings = meetings;
        this.jobs = jobs;
        this.runner = runner;
        this.roster = roster;
        this.unit = unit;
        this.dataDir = Path.of(dataDir);
    }

    /** participants 가 없으면(null) 명단 전체가 기본값이다 — 빈 배열은 "아무도 없음"이라 구분한다. */
    public record CreateRequest(String title, LocalDate heldOn, List<String> participants) {}

    /** 상태 값은 한글, JSON 키는 영문 — issue_candidate.state 관례를 따른다. */
    public record JobResponse(String state, int progressDone, int progressTotal, String failureReason) {}

    /** job 은 오디오를 올리기 전엔 null 이다 — 업로드 하나가 잡 하나다. */
    public record DetailResponse(String id, String title, LocalDate heldOn, List<String> participants,
                          boolean audioUploaded, JobResponse job) {}

    public record RowResponse(String id, String title, LocalDate heldOn) {}

    // ── 회의 ────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest request) {
        var title = request.title() == null ? "" : request.title().trim();
        if (title.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "제목을 입력하세요"));
        if (request.heldOn() == null) return ResponseEntity.badRequest().body(Map.of("error", "회의 날짜를 고르세요"));

        var id = unit.within(() -> {
            var participants = request.participants() == null ? roster.roster() : request.participants();
            return meetings.create(title, request.heldOn(), participants);
        });
        return rereadAndReturn(id);
    }

    @GetMapping
    public List<RowResponse> list() {
        return meetings.list().stream()
                .map(m -> new RowResponse(m.id().toString(), m.title(), m.heldOn()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String id) {
        return lookUp(id).<ResponseEntity<?>>map(m -> ResponseEntity.ok(toDetail(m))).orElseGet(MeetingController::notFound);
    }

    // ── 오디오 ──────────────────────────────────────────────

    /** 업로드가 곧 처리 시작이다. 재업로드(교체)는 범위 밖 — 실패는 재시도로 푼다. */
    @PostMapping("/{id}/audio")
    public ResponseEntity<?> uploadAudio(@PathVariable String id,
                                        @RequestParam("file") MultipartFile file) {
        var found = lookUp(id);
        if (found.isEmpty()) return notFound();
        if (found.get().audioPath() != null) return alreadyUploaded();   // 싼 거절 — 진짜 판정은 아래 조건부 UPDATE

        var meeting = found.get().id();
        var dir = dataDir.resolve(meeting.toString());
        var storedPath = dir.resolve(sanitizedFilename(file.getOriginalFilename()));
        var job = UUID.randomUUID();

        boolean claimed = unit.within(() -> {
            // 선점이 파일 쓰기보다 **먼저** 온다. 뒤집으면 경쟁에서 진 쪽이 이미 디스크에 쓴 뒤라,
            // 같은 파일명이면 이긴 쪽 오디오를 덮어쓴 채 409 를 돌려준다(워커가 그 파일을 읽는 중일 수 있다).
            if (!meetings.claimAudioPath(meeting, storedPath.toString())) return false;
            jobs.create(job, meeting);
            // 쓰기가 트랜잭션 안에 있으므로 실패하면 선점도 잡도 없던 일이 된다 — 대기중인 유령이 안 남는다.
            // 멀티파트는 이미 임시파일에 받아둔 뒤라 여기서는 로컬 복사다(네트워크를 붙들지 않는다).
            storeFile(file, dir, storedPath);
            return true;
        });
        if (!claimed) return alreadyUploaded();

        runner.submit(job, meeting, storedPath);   // 커밋 뒤에 제출한다 — 커밋 전이면 워커가 그 행을 못 본다
        return rereadAndReturn(meeting);
    }

    /** 재시도는 같은 행 리셋이다. 처리중·완료인 잡은 거절한다. */
    @PostMapping("/{id}/retry")
    public ResponseEntity<?> retry(@PathVariable String id) {
        var found = lookUp(id);
        if (found.isEmpty()) return notFound();

        var meeting = found.get().id();
        var job = jobs.jobOf(meeting);
        if (job.isEmpty()) return notFound();
        if (!unit.within(() -> jobs.resetForRetry(job.get().id())))
            return ResponseEntity.status(409).body(Map.of("error", "처리중이거나 완료된 잡은 재시도할 수 없다"));

        runner.submit(job.get().id(), meeting, Path.of(found.get().audioPath()));
        return rereadAndReturn(meeting);
    }

    // ── 내부 ────────────────────────────────────────────────

    /**
     * 회의 하나를 집는다. 잘못된 형식의 id 는 없는 회의와 같은 취급이다 —
     * 화면엔 둘 다 "없다"로 보인다.
     */
    private Optional<JooqMeetings.Detail> lookUp(String id) {
        UUID meeting;
        try { meeting = UUID.fromString(id); } catch (IllegalArgumentException e) { return Optional.empty(); }
        return meetings.detail(meeting);
    }

    /** 바꾼 뒤엔 다시 읽어서 준다 — 오디오·잡이 방금 생겼으니 집어온 것은 이미 낡았다. */
    private ResponseEntity<?> rereadAndReturn(UUID meeting) {
        var m = meetings.detail(meeting);
        return m.isEmpty() ? notFound() : ResponseEntity.ok(toDetail(m.get()));
    }

    private DetailResponse toDetail(JooqMeetings.Detail m) {
        return new DetailResponse(m.id().toString(), m.title(), m.heldOn(), m.participants(),
                            m.audioPath() != null,
                            jobs.jobOf(m.id())
                               .map(j -> new JobResponse(j.state(), j.done(), j.total(), j.failureReason()))
                               .orElse(null));
    }

    /**
     * 트랜잭션 안에서 부른다 — 던지면 선점이 롤백된다.
     * 검사 예외를 못 던지는 자리(단위작업은 Supplier 를 받는다)라 감싼다.
     */
    private static void storeFile(MultipartFile file, Path dir, Path storedPath) {
        try {
            Files.createDirectories(dir);
            file.transferTo(storedPath);
        } catch (IOException e) {
            throw new UncheckedIOException("오디오를 저장하지 못했다: " + storedPath, e);
        }
    }

    /**
     * 원본 파일명에서 경로 성분을 떼어낸다 — 업로드한 이름이 디렉토리를 벗어나면 안 된다.
     * 이름이 남지 않으면 audio 로 둔다.
     */
    static String sanitizedFilename(String original) {
        if (original == null || original.isBlank()) return "audio";
        var name = Path.of(original.trim()).getFileName();
        if (name == null) return "audio";
        var text = name.toString();
        return text.isBlank() || text.equals(".") || text.equals("..") ? "audio" : text;
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "없는 회의입니다"));
    }

    private static ResponseEntity<?> alreadyUploaded() {
        return ResponseEntity.status(409).body(Map.of("error", "이미 오디오를 올린 회의입니다"));
    }
}
