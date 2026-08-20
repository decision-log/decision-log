package dl.app.store;

import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Utterance;
import dl.domain.ports.저장소.*;
import org.jooq.DSLContext;

import java.util.*;

import static dl.app.jooq.Tables.*;

/**
 * 저장소 포트의 jOOQ 구현.
 *
 * 컬럼은 마이그레이션에서 생성된 타입이다 — 이름을 틀리면 컴파일이 죽는다.
 * 포트가 묶음을 받으므로 여기서 집합 연산을 쓸 수 있다.
 */
public final class JooqStores {

    public static final class 회의들 implements 회의저장소 {
        private final DSLContext db;
        public 회의들(DSLContext db) { this.db = db; }

        @Override public 회의ID 새회의() {
            var id = UUID.randomUUID();
            db.insertInto(MEETING).columns(MEETING.ID).values(id).execute();
            return new 회의ID(id.toString());
        }

        @Override public void 회의록저장(회의ID 회의, List<Utterance> 회의록) {
            if (회의록.isEmpty()) return;
            var m = UUID.fromString(회의.value());
            var step = db.insertInto(UTTERANCE)
                    .columns(UTTERANCE.MEETING_ID, UTTERANCE.SEQ, UTTERANCE.SPEAKER,
                             UTTERANCE.START_SEC, UTTERANCE.END_SEC, UTTERANCE.TEXT);
            for (int i = 0; i < 회의록.size(); i++) {
                var u = 회의록.get(i);
                step = step.values(m, i, u.speakerLabel(), u.startSec(), u.endSec(), u.text());
            }
            step.execute();
        }
    }

    public static final class 이슈들 implements 이슈저장소 {
        private final DSLContext db;
        public 이슈들(DSLContext db) { this.db = db; }

        @Override public void 후보저장(List<이슈후보> 후보들) {
            if (후보들.isEmpty()) return;
            var step = db.insertInto(ISSUE_CANDIDATE)
                    .columns(ISSUE_CANDIDATE.ID, ISSUE_CANDIDATE.MEETING_ID, ISSUE_CANDIDATE.QUESTION,
                             ISSUE_CANDIDATE.STATE, ISSUE_CANDIDATE.ANSWER, ISSUE_CANDIDATE.SPANS);
            for (var c : 후보들) {
                step = step.values(UUID.fromString(c.id().value()), UUID.fromString(c.회의().value()),
                        c.질문(), c.상태().name(), c.답(), c.근거구간().toArray(new Integer[0]));
            }
            step.execute();
        }

        @Override public List<이슈후보> 미확인후보(회의ID 회의) {
            var m = UUID.fromString(회의.value());
            return db.selectFrom(ISSUE_CANDIDATE)
                     .where(ISSUE_CANDIDATE.MEETING_ID.eq(m))
                     .and(ISSUE_CANDIDATE.PROMOTED.isFalse())
                     .fetch(r -> new 이슈후보(
                             new 이슈ID(r.get(ISSUE_CANDIDATE.ID).toString()), 회의,
                             r.get(ISSUE_CANDIDATE.QUESTION),
                             상태.valueOf(r.get(ISSUE_CANDIDATE.STATE)),
                             r.get(ISSUE_CANDIDATE.ANSWER),
                             Arrays.asList(r.get(ISSUE_CANDIDATE.SPANS))));
        }

        /** 후보가 자바를 왕복하지 않는다 — DB 안에서 옮긴다. */
        @Override public void 승격(List<이슈ID> ids) {
            if (ids.isEmpty()) return;
            var uuids = ids.stream().map(i -> UUID.fromString(i.value())).toList();

            db.insertInto(ISSUE, ISSUE.ID, ISSUE.QUESTION, ISSUE.STATE, ISSUE.ANSWER)
              .select(db.select(ISSUE_CANDIDATE.ID, ISSUE_CANDIDATE.QUESTION,
                                ISSUE_CANDIDATE.STATE, ISSUE_CANDIDATE.ANSWER)
                        .from(ISSUE_CANDIDATE)
                        .where(ISSUE_CANDIDATE.ID.in(uuids)))
              .onConflict(ISSUE.ID).doNothing()
              .execute();

            db.update(ISSUE_CANDIDATE).set(ISSUE_CANDIDATE.PROMOTED, true)
              .where(ISSUE_CANDIDATE.ID.in(uuids)).execute();
        }

        @Override public List<이슈> 전량() {
            return db.selectFrom(ISSUE).fetch(r -> new 이슈(
                    new 이슈ID(r.get(ISSUE.ID).toString()), r.get(ISSUE.QUESTION),
                    상태.valueOf(r.get(ISSUE.STATE)), r.get(ISSUE.ANSWER)));
        }

        @Override public Optional<이슈> 찾기(이슈ID id) {
            return db.selectFrom(ISSUE).where(ISSUE.ID.eq(UUID.fromString(id.value())))
                     .fetchOptional()
                     .map(r -> new 이슈(id, r.get(ISSUE.QUESTION),
                             상태.valueOf(r.get(ISSUE.STATE)), r.get(ISSUE.ANSWER)));
        }
    }

    public static final class 용어집 implements 용어집저장소 {
        private final DSLContext db;
        public 용어집(DSLContext db) { this.db = db; }

        /** 존재 여부를 묻지 않는다 — upsert 한 방이 대신한다. */
        @Override public void 추가(List<용어> 용어들) {
            if (용어들.isEmpty()) return;
            var step = db.insertInto(GLOSSARY).columns(GLOSSARY.표기, GLOSSARY.뜻);
            for (var t : 용어들) step = step.values(t.표기(), t.뜻());
            step.onConflict(GLOSSARY.표기).doNothing().execute();
        }

        @Override public List<용어> 전량() {
            return db.selectFrom(GLOSSARY).fetch(r -> new 용어(r.get(GLOSSARY.표기), r.get(GLOSSARY.뜻)));
        }
    }
}
