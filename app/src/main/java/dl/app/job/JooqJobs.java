package dl.app.job;

import org.jooq.DSLContext;

import java.util.Optional;
import java.util.UUID;

import static dl.app.jooq.Tables.JOB;
import static org.jooq.impl.DSL.currentOffsetDateTime;

/**
 * 잡 테이블 접근. 도메인 포트가 아니다 — 잡은 app 의 기계장치다(설계 합의문 "거처").
 *
 * 상태를 바꾸는 것은 전부 조건부 UPDATE 다. "읽고 판단하고 쓰기"로 하면 두 스레드가
 * 같은 잡을 동시에 집는 창이 열린다 — 재시도와 워커가 정확히 그 창에서 만난다.
 */
public final class JooqJobs {

    // 상태값은 한글 텍스트 — issue_candidate.state 관례
    public static final String 대기중 = "대기중";
    public static final String 처리중 = "처리중";
    public static final String 완료 = "완료";
    public static final String 실패 = "실패";

    /** 부팅 쓸어담기가 남기는 사유. 자동 재개는 없다 — 사람이 재시도를 누른다. */
    public static final String 재시작사유 = "애플리케이션 재시작으로 중단됨";

    private final DSLContext db;
    public JooqJobs(DSLContext db) { this.db = db; }

    public record 잡행(UUID id, UUID 회의, String 상태, int 완료, int 전체, String 실패사유) {}

    public void 생성(UUID 잡, UUID 회의) {
        db.insertInto(JOB)
          .columns(JOB.ID, JOB.MEETING_ID, JOB.STATE)
          .values(잡, 회의, 대기중)
          .execute();
    }

    /**
     * 워커가 일을 집는다. 대기중일 때만 처리중이 된다 — 0행이면 이미 누가 집었다는 뜻이라
     * 워커는 조용히 물러난다(이중 제출 방어).
     */
    public boolean 시작표시(UUID 잡) {
        return db.update(JOB)
                 .set(JOB.STATE, 처리중)
                 .set(JOB.UPDATED_AT, currentOffsetDateTime())
                 .where(JOB.ID.eq(잡)).and(JOB.STATE.eq(대기중))
                 .execute() == 1;
    }

    /** 진행률은 처리 구현이 임의의 분수로 보고한다 — 잡 테이블엔 정수 두 칸뿐이다. */
    public void 진행기록(UUID 잡, int 완료수, int 전체수) {
        db.update(JOB)
          .set(JOB.PROGRESS_DONE, 완료수)
          .set(JOB.PROGRESS_TOTAL, 전체수)
          .set(JOB.UPDATED_AT, currentOffsetDateTime())
          .where(JOB.ID.eq(잡)).and(JOB.STATE.eq(처리중))
          .execute();
    }

    public void 완료표시(UUID 잡) {
        db.update(JOB)
          .set(JOB.STATE, 완료)
          .set(JOB.FAILURE_REASON, (String) null)
          .set(JOB.UPDATED_AT, currentOffsetDateTime())
          .where(JOB.ID.eq(잡)).and(JOB.STATE.eq(처리중))
          .execute();
    }

    public void 실패표시(UUID 잡, String 사유) {
        db.update(JOB)
          .set(JOB.STATE, 실패)
          .set(JOB.FAILURE_REASON, 사유)
          .set(JOB.UPDATED_AT, currentOffsetDateTime())
          .where(JOB.ID.eq(잡)).and(JOB.STATE.eq(처리중))
          .execute();
    }

    /**
     * 재시도는 같은 행 리셋이다. 실패한 행만 대기중으로 되돌린다 —
     * 0행이면 거절이다(처리중·완료 재시도 경쟁 차단). 시도 이력은 남기지 않는다.
     */
    public boolean 재시도리셋(UUID 잡) {
        return db.update(JOB)
                 .set(JOB.STATE, 대기중)
                 .set(JOB.PROGRESS_DONE, 0)
                 .set(JOB.PROGRESS_TOTAL, 0)
                 .set(JOB.FAILURE_REASON, (String) null)
                 .set(JOB.UPDATED_AT, currentOffsetDateTime())
                 .where(JOB.ID.eq(잡)).and(JOB.STATE.eq(실패))
                 .execute() == 1;
    }

    /**
     * 부팅 쓸어담기 — 대기중·처리중으로 남은 잡을 전부 실패로 바꾼다.
     * 프로세스가 죽으면서 그 잡을 돌던 스레드도 같이 죽었으므로, 남은 행은 전부 유령이다.
     */
    public int 쓸어담기() {
        return db.update(JOB)
                 .set(JOB.STATE, 실패)
                 .set(JOB.FAILURE_REASON, 재시작사유)
                 .set(JOB.UPDATED_AT, currentOffsetDateTime())
                 .where(JOB.STATE.in(대기중, 처리중))
                 .execute();
    }

    /** 회의당 잡 유니크 — 업로드 하나가 잡 하나다. */
    public Optional<잡행> 회의의잡(UUID 회의) {
        return db.select(JOB.ID, JOB.MEETING_ID, JOB.STATE, JOB.PROGRESS_DONE,
                         JOB.PROGRESS_TOTAL, JOB.FAILURE_REASON)
                 .from(JOB).where(JOB.MEETING_ID.eq(회의))
                 .fetchOptional()
                 .map(r -> new 잡행(r.get(JOB.ID), r.get(JOB.MEETING_ID), r.get(JOB.STATE),
                                    r.get(JOB.PROGRESS_DONE), r.get(JOB.PROGRESS_TOTAL),
                                    r.get(JOB.FAILURE_REASON)));
    }
}
