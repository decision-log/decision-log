package dl.app.store;

import org.jooq.DSLContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dl.app.jooq.Tables.MEETING;
import static dl.app.jooq.Tables.MEETING_PARTICIPANT;

/**
 * 회의 테이블 접근. 도메인 포트가 아니다 — 사람이 만드는 회의는 app 의 일이다.
 *
 * (도메인의 회의저장소는 회차오케스트레이터가 스스로 회의를 만드는 경로를 위한 것이고,
 *  그 둘의 충돌 해소는 진짜 연결 티켓의 몫으로 미뤄져 있다.)
 */
public final class JooqMeetings {
    private final DSLContext db;
    public JooqMeetings(DSLContext db) { this.db = db; }

    /** 화면이 읽는 회의 하나. 참가자는 그 회의가 찍힌 시점의 스냅샷이다. */
    public record Detail(UUID id, String title, LocalDate heldOn, List<String> participants, String audioPath) {}

    /** 목록 줄 하나 — 상세를 열기 전에 보이는 것만. */
    public record Row(UUID id, String title, LocalDate heldOn) {}

    /** 참가자는 명단을 참조하지 않고 복사한다 — 명단이 바뀌어도 이 회의의 사실은 안 변한다. */
    public UUID create(String title, LocalDate heldOn, List<String> participants) {
        var id = UUID.randomUUID();
        db.insertInto(MEETING)
          .columns(MEETING.ID, MEETING.TITLE, MEETING.HELD_ON)
          .values(id, title, heldOn)
          .execute();

        if (!participants.isEmpty()) {
            var step = db.insertInto(MEETING_PARTICIPANT)
                         .columns(MEETING_PARTICIPANT.MEETING_ID, MEETING_PARTICIPANT.NAME);
            for (var name : participants) step = step.values(id, name);
            step.onConflict(MEETING_PARTICIPANT.MEETING_ID, MEETING_PARTICIPANT.NAME)
                .doNothing()
                .execute();
        }
        return id;
    }

    /** 최근 회의가 위로 — 같은 날이면 만든 순서의 역순. */
    public List<Row> list() {
        return db.select(MEETING.ID, MEETING.TITLE, MEETING.HELD_ON)
                 .from(MEETING)
                 .orderBy(MEETING.HELD_ON.desc(), MEETING.STARTED_AT.desc())
                 .fetch(r -> new Row(r.get(MEETING.ID), r.get(MEETING.TITLE), r.get(MEETING.HELD_ON)));
    }

    /**
     * 오디오 경로 선점. 비어 있을 때만 기록된다 — 0행이면 이미 누가 올렸다는 뜻이다.
     * 재업로드(교체)는 범위 밖이라 덮어쓰기가 아니라 거절이다.
     */
    public boolean claimAudioPath(UUID id, String path) {
        return db.update(MEETING)
                 .set(MEETING.AUDIO_PATH, path)
                 .where(MEETING.ID.eq(id)).and(MEETING.AUDIO_PATH.isNull())
                 .execute() == 1;
    }

    public Optional<Detail> detail(UUID id) {
        return db.select(MEETING.ID, MEETING.TITLE, MEETING.HELD_ON, MEETING.AUDIO_PATH)
                 .from(MEETING).where(MEETING.ID.eq(id))
                 .fetchOptional()
                 .map(r -> new Detail(r.get(MEETING.ID), r.get(MEETING.TITLE), r.get(MEETING.HELD_ON),
                                    participants(id), r.get(MEETING.AUDIO_PATH)));
    }

    /** 순서는 의미를 갖지 않는다 — 명단과 같이 이름순으로 준다. */
    private List<String> participants(UUID meeting) {
        return db.select(MEETING_PARTICIPANT.NAME).from(MEETING_PARTICIPANT)
                 .where(MEETING_PARTICIPANT.MEETING_ID.eq(meeting))
                 .orderBy(MEETING_PARTICIPANT.NAME)
                 .fetch(r -> r.get(MEETING_PARTICIPANT.NAME));
    }
}
