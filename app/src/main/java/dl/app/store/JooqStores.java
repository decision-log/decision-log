package dl.app.store;

import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Utterance;
import dl.domain.ports.Stores.*;
import org.jooq.DSLContext;

import java.time.ZoneOffset;
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
        @Override public TranscriptId saveTranscript(MeetingId meeting, List<Utterance> transcript) {
            var m = UUID.fromString(meeting.value());

            var transcriptId = UUID.randomUUID();
            int next = db.select(coalesce(max(TRANSCRIPT.SEQ), inline(-1)))
                        .from(TRANSCRIPT).where(TRANSCRIPT.MEETING_ID.eq(m))
                        .fetchSingle().value1() + 1;
            db.insertInto(TRANSCRIPT)
              .columns(TRANSCRIPT.ID, TRANSCRIPT.MEETING_ID, TRANSCRIPT.SEQ)
              .values(transcriptId, m, next)
              .execute();

            if (!transcript.isEmpty()) {
                var step = db.insertInto(UTTERANCE)
                        .columns(UTTERANCE.TRANSCRIPT_ID, UTTERANCE.SEQ, UTTERANCE.SPEAKER,
                                 UTTERANCE.START_SEC, UTTERANCE.END_SEC, UTTERANCE.TEXT);
                for (int i = 0; i < transcript.size(); i++) {
                    var u = transcript.get(i);
                    step = step.values(transcriptId, i, u.speakerLabel(), u.startSec(), u.endSec(), u.text());
                }
                step.execute();
            }
            return new TranscriptId(transcriptId.toString());
        }

        /** 덩이 seq 순, 덩이 안에서는 발화 seq 순. 이어붙인 순서가 곧 추출이 보는 전역 번호다. */
        @Override public List<Transcript> fullTranscript(MeetingId meeting) {
            var rows = db.select(TRANSCRIPT.ID, UTTERANCE.SPEAKER, UTTERANCE.START_SEC,
                                 UTTERANCE.END_SEC, UTTERANCE.TEXT)
                    .from(TRANSCRIPT)
                    .leftJoin(UTTERANCE).on(UTTERANCE.TRANSCRIPT_ID.eq(TRANSCRIPT.ID))
                    .where(TRANSCRIPT.MEETING_ID.eq(UUID.fromString(meeting.value())))
                    .orderBy(TRANSCRIPT.SEQ, UTTERANCE.SEQ)
                    .fetch();

            var chunks = new LinkedHashMap<UUID, List<Utterance>>();
            for (var r : rows) {
                var utterances = chunks.computeIfAbsent(r.get(TRANSCRIPT.ID), id -> new ArrayList<>());
                if (r.get(UTTERANCE.TEXT) == null) continue;      // 발화가 없는 덩이 — 자리만 남긴다
                utterances.add(new Utterance(r.get(UTTERANCE.SPEAKER), r.get(UTTERANCE.START_SEC),
                        r.get(UTTERANCE.END_SEC), r.get(UTTERANCE.TEXT)));
            }
            return chunks.entrySet().stream()
                    .map(e -> new Transcript(new TranscriptId(e.getKey().toString()), e.getValue()))
                    .toList();
        }
    }

    /**
     * 벌 하나가 한 단위다. 트랜잭션은 오케스트레이터의 단위작업이 감싼다 — 여기는 모른다.
     */
    public static final class Extractions implements ExtractionStore {
        private final DSLContext db;
        public Extractions(DSLContext db) { this.db = db; }

        /** 벌 하나를 통째로 넣고 회의의 현재 벌 포인터를 옮긴다. 묶음마다 배치 insert 한 방씩이다. */
        @Override public void save(Extraction extraction) {
            var id = UUID.fromString(extraction.id().value());
            var meeting = UUID.fromString(extraction.meeting().value());
            var meta = extraction.meta();
            var tokens = meta.tokens();

            db.insertInto(EXTRACTION)
              .columns(EXTRACTION.ID, EXTRACTION.MEETING_ID, EXTRACTION.MODEL_NAME, EXTRACTION.PROMPT_VERSION,
                       EXTRACTION.PROMPT_HASH, EXTRACTION.TOKENS_INPUT, EXTRACTION.TOKENS_OUTPUT,
                       EXTRACTION.TOKENS_CACHE_HIT, EXTRACTION.CREATED_AT)
              .values(id, meeting, meta.modelName(), meta.promptVersion(), meta.promptHash(),
                      tokens == null ? null : tokens.input(),
                      tokens == null ? null : tokens.output(),
                      tokens == null ? null : tokens.cacheHit(),
                      extraction.createdAt().atOffset(ZoneOffset.UTC))
              .execute();

            if (!extraction.issueCandidates().isEmpty()) {
                var step = db.insertInto(ISSUE_CANDIDATE)
                        .columns(ISSUE_CANDIDATE.ID, ISSUE_CANDIDATE.EXTRACTION_ID, ISSUE_CANDIDATE.QUESTION,
                                 ISSUE_CANDIDATE.STATE, ISSUE_CANDIDATE.ANSWER, ISSUE_CANDIDATE.UNDECIDED_REASON);
                for (var c : extraction.issueCandidates())
                    step = step.values(UUID.fromString(c.id().value()), id, c.content().question(),
                            c.content().state().name(), c.content().answer(), c.content().undecidedReason());
                step.execute();
            }

            if (!extraction.termCandidates().isEmpty()) {
                var step = db.insertInto(TERM_CANDIDATE)
                        .columns(TERM_CANDIDATE.ID, TERM_CANDIDATE.EXTRACTION_ID,
                                 TERM_CANDIDATE.SPELLING, TERM_CANDIDATE.MEANING);
                for (var t : extraction.termCandidates())
                    step = step.values(UUID.fromString(t.id().value()), id,
                            t.content().spelling(), t.content().meaning());
                step.execute();
            }

            if (!extraction.opinions().isEmpty()) {
                var step = db.insertInto(OPINION)
                        .columns(OPINION.ID, OPINION.EXTRACTION_ID, OPINION.ISSUE_CANDIDATE_ID,
                                 OPINION.SPEAKER_LABEL, OPINION.TEXT,
                                 OPINION.EVIDENCE_TRANSCRIPT, OPINION.EVIDENCE_SEQ);
                for (var o : extraction.opinions())
                    step = step.values(UUID.fromString(o.id().value()), id, candidateId(o.issue()),
                            o.content().speakerLabel(), o.content().text(),
                            UUID.fromString(o.evidence().transcript().value()), o.evidence().seq());
                step.execute();
            }

            if (!extraction.tasks().isEmpty()) {
                var step = db.insertInto(TASK)
                        .columns(TASK.ID, TASK.EXTRACTION_ID, TASK.ISSUE_CANDIDATE_ID,
                                 TASK.TEXT, TASK.ASSIGNEE, TASK.EVIDENCE_TRANSCRIPT, TASK.EVIDENCE_SEQ);
                for (var t : extraction.tasks())
                    step = step.values(UUID.fromString(t.id().value()), id, candidateId(t.issue()),
                            t.content().text(), t.content().assignee(),
                            UUID.fromString(t.evidence().transcript().value()), t.evidence().seq());
                step.execute();
            }

            var candidateEvidence = db.insertInto(ISSUE_CANDIDATE_EVIDENCE)
                    .columns(ISSUE_CANDIDATE_EVIDENCE.CANDIDATE_ID, ISSUE_CANDIDATE_EVIDENCE.TRANSCRIPT_ID,
                             ISSUE_CANDIDATE_EVIDENCE.SEQ);
            int candidateRows = 0;
            for (var c : extraction.issueCandidates())
                for (var e : c.evidence()) {
                    candidateEvidence = candidateEvidence.values(UUID.fromString(c.id().value()),
                            UUID.fromString(e.transcript().value()), e.seq());
                    candidateRows++;
                }
            if (candidateRows > 0) candidateEvidence.execute();

            var termEvidence = db.insertInto(TERM_CANDIDATE_EVIDENCE)
                    .columns(TERM_CANDIDATE_EVIDENCE.TERM_CANDIDATE_ID, TERM_CANDIDATE_EVIDENCE.TRANSCRIPT_ID,
                             TERM_CANDIDATE_EVIDENCE.SEQ);
            int termRows = 0;
            for (var t : extraction.termCandidates())
                for (var e : t.evidence()) {
                    termEvidence = termEvidence.values(UUID.fromString(t.id().value()),
                            UUID.fromString(e.transcript().value()), e.seq());
                    termRows++;
                }
            if (termRows > 0) termEvidence.execute();

            db.update(MEETING).set(MEETING.CURRENT_EXTRACTION_ID, id)
              .where(MEETING.ID.eq(meeting)).execute();
        }

        @Override public List<IssueCandidate> unconfirmedCandidates(MeetingId meeting) {
            var current = currentExtraction(meeting);
            if (current == null) return List.of();

            var rows = db.selectFrom(ISSUE_CANDIDATE)
                    .where(ISSUE_CANDIDATE.EXTRACTION_ID.eq(current))
                    .and(ISSUE_CANDIDATE.PROMOTED_ISSUE_ID.isNull())
                    .fetch();
            if (rows.isEmpty()) return List.of();

            var evidence = evidenceOf(ISSUE_CANDIDATE_EVIDENCE.CANDIDATE_ID, ISSUE_CANDIDATE_EVIDENCE.TRANSCRIPT_ID,
                    ISSUE_CANDIDATE_EVIDENCE.SEQ, rows.stream().map(r -> r.get(ISSUE_CANDIDATE.ID)).toList());

            return rows.stream().map(r -> new IssueCandidate(
                    new CandidateId(r.get(ISSUE_CANDIDATE.ID).toString()),
                    new ExtractionId(current.toString()),
                    new IssueCandidate.Content(r.get(ISSUE_CANDIDATE.QUESTION),
                            ProposedState.valueOf(r.get(ISSUE_CANDIDATE.STATE)),
                            r.get(ISSUE_CANDIDATE.ANSWER), r.get(ISSUE_CANDIDATE.UNDECIDED_REASON)),
                    evidence.getOrDefault(r.get(ISSUE_CANDIDATE.ID), List.of()))).toList();
        }

        @Override public List<TermCandidate> termCandidates(MeetingId meeting) {
            var current = currentExtraction(meeting);
            if (current == null) return List.of();

            var rows = db.selectFrom(TERM_CANDIDATE).where(TERM_CANDIDATE.EXTRACTION_ID.eq(current)).fetch();
            if (rows.isEmpty()) return List.of();

            var evidence = evidenceOf(TERM_CANDIDATE_EVIDENCE.TERM_CANDIDATE_ID, TERM_CANDIDATE_EVIDENCE.TRANSCRIPT_ID,
                    TERM_CANDIDATE_EVIDENCE.SEQ, rows.stream().map(r -> r.get(TERM_CANDIDATE.ID)).toList());

            return rows.stream().map(r -> new TermCandidate(
                    new TermCandidateId(r.get(TERM_CANDIDATE.ID).toString()),
                    new ExtractionId(current.toString()),
                    new TermCandidate.Content(r.get(TERM_CANDIDATE.SPELLING), r.get(TERM_CANDIDATE.MEANING)),
                    evidence.getOrDefault(r.get(TERM_CANDIDATE.ID), List.of()))).toList();
        }

        private UUID currentExtraction(MeetingId meeting) {
            return db.select(MEETING.CURRENT_EXTRACTION_ID).from(MEETING)
                     .where(MEETING.ID.eq(UUID.fromString(meeting.value())))
                     .fetchOne(MEETING.CURRENT_EXTRACTION_ID);
        }

        /**
         * 근거를 소유자 ID 들로 <b>한 방에</b> 걷는다 — 왕복이 후보 수에 안 비례한다.
         * 순서는 덩이 seq 다음 발화 seq — 회의록에 나온 순서와 같다.
         */
        private Map<UUID, List<Evidence>> evidenceOf(org.jooq.TableField<?, UUID> owner,
                                                     org.jooq.TableField<?, UUID> transcript,
                                                     org.jooq.TableField<?, Integer> seq,
                                                     List<UUID> ids) {
            var out = new LinkedHashMap<UUID, List<Evidence>>();
            db.select(owner, transcript, seq)
              .from(owner.getTable())
              .join(TRANSCRIPT).on(TRANSCRIPT.ID.eq(transcript))
              .where(owner.in(ids))
              .orderBy(TRANSCRIPT.SEQ, seq)
              .forEach(r -> out.computeIfAbsent(r.get(owner), id -> new ArrayList<>())
                      .add(new Evidence(new TranscriptId(r.get(transcript).toString()), r.get(seq))));
            return out;
        }

        private static UUID candidateId(CandidateId id) {
            return id == null ? null : UUID.fromString(id.value());
        }
    }

    public static final class Issues implements IssueStore {
        private final DSLContext db;
        public Issues(DSLContext db) { this.db = db; }

        /**
         * 후보가 자바를 왕복하지 않는다 — DB 안에서 한 문장으로 옮긴다.
         *
         * <p>{@code picked} 가 두 번 참조되므로 Postgres 가 한 번만 실행하고 결과를 물린다.
         * 그래서 {@code gen_random_uuid()} 가 만든 <b>새 이슈 ID</b> 를 insert 와 update 가 같이 본다 —
         * 후보 ID 와 값이 겹치지 않는 이슈가 생기고, 어느 이슈가 됐는지가 후보 행에 남는다.
         *
         * <p>타입 안전 DSL 로는 데이터를 바꾸는 CTE 를 못 세워 원문 SQL 한 문장을 쓴다.
         * 후보 ID 는 전부 바인드 변수다.
         */
        private static final String PROMOTE = """
                with picked as (
                    select id, gen_random_uuid() as issue_id, question, state, answer
                      from issue_candidate
                     where id in (%s) and promoted_issue_id is null
                ), inserted as (
                    insert into issue (id, question, state, answer)
                    select issue_id, question, state, answer from picked
                )
                update issue_candidate c
                   set promoted_issue_id = picked.issue_id
                  from picked
                 where c.id = picked.id
                """;

        @Override public void promote(List<CandidateId> ids) {
            if (ids.isEmpty()) return;
            var uuids = ids.stream().map(i -> UUID.fromString(i.value())).toArray();
            var placeholders = String.join(", ", Collections.nCopies(uuids.length, "?"));
            db.query(PROMOTE.formatted(placeholders), uuids).execute();
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
