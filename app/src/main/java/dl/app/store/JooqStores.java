package dl.app.store;

import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Utterance;
import dl.domain.ports.Stores.*;
import org.jooq.DSLContext;

import java.util.*;

import static dl.app.jooq.Tables.*;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.max;

/**
 * 저장소 포트의 jOOQ 구현.
 *
 * 컬럼은 마이그레이션에서 생성된 타입이다 — 이름을 틀리면 컴파일이 죽는다.
 * 포트가 묶음을 받으므로 여기서 집합 연산을 쓸 수 있다.
 */
public final class JooqStores {

    public static final class Meetings implements MeetingStore {
        private final DSLContext db;
        public Meetings(DSLContext db) { this.db = db; }

        @Override public MeetingId newMeeting() {
            var id = UUID.randomUUID();
            db.insertInto(MEETING).columns(MEETING.ID).values(id).execute();
            return new MeetingId(id.toString());
        }

        /**
         * 회의록 한 벌을 덧붙인다 — 회의 안에서 다음 seq 로 들어간다.
         *
         * <p>seq 를 읽고 쓰는 사이가 열려 있지만 회의당 잡이 하나고 그 잡을 집는 일꾼도
         * 하나라(JooqJobs.시작표시) 같은 회의에 두 벌이 동시에 들어오는 경로가 없다.
         * 뚫리면 {@code unique (meeting_id, seq)} 가 막는다.
         */
        @Override public void saveTranscript(MeetingId meeting, List<Utterance> transcript) {
            if (transcript.isEmpty()) return;
            var m = UUID.fromString(meeting.value());

            var transcriptId = UUID.randomUUID();
            int next = db.select(coalesce(max(TRANSCRIPT.SEQ), inline(-1)))
                        .from(TRANSCRIPT).where(TRANSCRIPT.MEETING_ID.eq(m))
                        .fetchSingle().value1() + 1;
            db.insertInto(TRANSCRIPT)
              .columns(TRANSCRIPT.ID, TRANSCRIPT.MEETING_ID, TRANSCRIPT.SEQ)
              .values(transcriptId, m, next)
              .execute();

            var step = db.insertInto(UTTERANCE)
                    .columns(UTTERANCE.TRANSCRIPT_ID, UTTERANCE.SEQ, UTTERANCE.SPEAKER,
                             UTTERANCE.START_SEC, UTTERANCE.END_SEC, UTTERANCE.TEXT);
            for (int i = 0; i < transcript.size(); i++) {
                var u = transcript.get(i);
                step = step.values(transcriptId, i, u.speakerLabel(), u.startSec(), u.endSec(), u.text());
            }
            step.execute();
        }
    }

    public static final class Issues implements IssueStore {
        private final DSLContext db;
        public Issues(DSLContext db) { this.db = db; }

        @Override public void saveCandidates(List<IssueCandidate> candidates) {
            if (candidates.isEmpty()) return;
            var step = db.insertInto(ISSUE_CANDIDATE)
                    .columns(ISSUE_CANDIDATE.ID, ISSUE_CANDIDATE.MEETING_ID, ISSUE_CANDIDATE.QUESTION,
                             ISSUE_CANDIDATE.STATE, ISSUE_CANDIDATE.ANSWER, ISSUE_CANDIDATE.SPANS);
            for (var c : candidates) {
                step = step.values(UUID.fromString(c.id().value()), UUID.fromString(c.meeting().value()),
                        c.question(), c.state().name(), c.answer(), c.evidenceSpans().toArray(new Integer[0]));
            }
            step.execute();
        }

        @Override public List<IssueCandidate> unconfirmedCandidates(MeetingId meeting) {
            var m = UUID.fromString(meeting.value());
            return db.selectFrom(ISSUE_CANDIDATE)
                     .where(ISSUE_CANDIDATE.MEETING_ID.eq(m))
                     .and(ISSUE_CANDIDATE.PROMOTED.isFalse())
                     .fetch(r -> new IssueCandidate(
                             new IssueId(r.get(ISSUE_CANDIDATE.ID).toString()), meeting,
                             r.get(ISSUE_CANDIDATE.QUESTION),
                             State.valueOf(r.get(ISSUE_CANDIDATE.STATE)),
                             r.get(ISSUE_CANDIDATE.ANSWER),
                             Arrays.asList(r.get(ISSUE_CANDIDATE.SPANS))));
        }

        /** 후보가 자바를 왕복하지 않는다 — DB 안에서 옮긴다. */
        @Override public void promote(List<IssueId> ids) {
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

        @Override public List<Issue> all() {
            return db.selectFrom(ISSUE).fetch(r -> new Issue(
                    new IssueId(r.get(ISSUE.ID).toString()), r.get(ISSUE.QUESTION),
                    State.valueOf(r.get(ISSUE.STATE)), r.get(ISSUE.ANSWER)));
        }

        @Override public Optional<Issue> find(IssueId id) {
            return db.selectFrom(ISSUE).where(ISSUE.ID.eq(UUID.fromString(id.value())))
                     .fetchOptional()
                     .map(r -> new Issue(id, r.get(ISSUE.QUESTION),
                             State.valueOf(r.get(ISSUE.STATE)), r.get(ISSUE.ANSWER)));
        }
    }

    public static final class Glossary implements GlossaryStore {
        private final DSLContext db;
        public Glossary(DSLContext db) { this.db = db; }

        /** 존재 여부를 묻지 않는다 — upsert 한 방이 대신한다. */
        @Override public void add(List<Term> terms) {
            if (terms.isEmpty()) return;
            var step = db.insertInto(GLOSSARY).columns(GLOSSARY.SPELLING, GLOSSARY.MEANING);
            for (var t : terms) step = step.values(t.spelling(), t.meaning());
            step.onConflict(GLOSSARY.SPELLING).doNothing().execute();
        }

        @Override public List<Term> all() {
            return db.selectFrom(GLOSSARY).fetch(r -> new Term(r.get(GLOSSARY.SPELLING), r.get(GLOSSARY.MEANING)));
        }

        /**
         * 표기가 열쇠라 update 한 방이면 된다 — 충돌만 미리 본다.
         * 확인과 갱신 사이의 레이스는 6명 규모라 감수한다. 뚫리면 표기 primary key 가 막는다.
         */
        @Override public void edit(String oldSpelling, String newSpelling, String newMeaning) {
            if (!oldSpelling.equals(newSpelling) && db.fetchExists(GLOSSARY, GLOSSARY.SPELLING.eq(newSpelling)))
                throw new SpellingConflict(newSpelling);

            int updated = db.update(GLOSSARY)
                          .set(GLOSSARY.SPELLING, newSpelling).set(GLOSSARY.MEANING, newMeaning)
                          .where(GLOSSARY.SPELLING.eq(oldSpelling))
                          .execute();
            if (updated == 0) throw new NoSuchElementException(oldSpelling);
        }
    }

    /** 명단은 통째 교체다 — 지우고 한 방에 넣는다. 원자성은 호출자가 단위작업으로 감싼다. */
    public static final class Roster implements RosterStore {
        private final DSLContext db;
        public Roster(DSLContext db) { this.db = db; }

        @Override public void saveRoster(List<String> names) {
            db.deleteFrom(PARTICIPANT).execute();
            if (names.isEmpty()) return;
            var step = db.insertInto(PARTICIPANT).columns(PARTICIPANT.NAME);
            for (var name : names) step = step.values(name);
            step.execute();
        }

        @Override public List<String> roster() {
            return db.select(PARTICIPANT.NAME).from(PARTICIPANT)
                     .orderBy(PARTICIPANT.NAME)
                     .fetch(r -> r.get(PARTICIPANT.NAME));
        }
    }
}
