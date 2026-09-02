package dl.domain;

import dl.domain.model.Model.*;
import dl.domain.ports.*;
import dl.domain.ports.ExtractPort.*;
import dl.domain.ports.SttPort.*;
import dl.domain.ports.Stores.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어댑터가 만든 참조는 포트를 넘을 때까지만 산다 (seams.md ⓶) — 그 해소가 여기서 일어난다.
 *
 * <p>손으로 만든 스텁만 쓴다. 오케스트레이터가 아는 것은 "여기부터 여기까지가 한 단위"뿐이라
 * 프레임워크도 DB 도 필요 없는 것이 이 경계의 값이다.
 */
class RoundOrchestratorTest {

    private static final MeetingId MEETING = new MeetingId("m1");
    private static final Meta META = new Meta("marker", "1", "deadbeef", null);

    // ── ① 전역 구간 번호가 (회의록, 순번) 이 된다 ─────────────

    /** 합의문 그림 그대로: {@code [t0#0 t0#1 t0#2 | t1#0 t1#1]} 에서 전역 3 → {@code (t1, 0)}. */
    @Test
    void 전역_구간번호가_회의록과_순번으로_해소된다() {
        var meetings = new StubMeetings();
        meetings.saveTranscript(MEETING, utterances("a", "b", "c"));
        meetings.saveTranscript(MEETING, utterances("d", "e"));
        var extractions = new StubExtractions();

        orchestrator(meetings, extractions, port(new ExtractionResult(META,
                List.of(new ExtractedCandidate(new LocalKey("proxy"), 쟁점("무엇으로 할 것인가"), List.of(3))),
                List.of(), List.of(), List.of())))
                .extract(MEETING, "1");

        assertThat(extractions.saved.issueCandidates().getFirst().evidence())
                .containsExactly(new Evidence(new TranscriptId("t1"), 0));
    }

    @Test
    void 회의록이_없는_회의는_추출할_수_없다() {
        var orchestrator = orchestrator(new StubMeetings(), new StubExtractions(), port(empty()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.extract(MEETING, "1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("회의록");
    }

    // ── ② 재시도가 전사를 건너뛴다 ───────────────────────────

    /**
     * 회의록이 이미 있으면 STT 를 안 부른다. 안 그러면 재시도가 회의록을 덧붙이고
     * 추출이 합쳐서 한 번 도니 <b>같은 회의가 두 번 들어가 이슈가 정확히 두 배로 뽑힌다</b> —
     * 그리고 에러가 안 난다. 여기서는 STT 스텁이 불리면 던지는 것으로 그 자리를 못 박는다.
     */
    @Test
    void 회의록이_이미_있으면_전사를_건너뛴다() {
        var meetings = new StubMeetings();
        meetings.saveTranscript(MEETING, utterances("이미 전사된 회의록"));

        orchestrator(meetings, new StubExtractions(), port(empty()))
                .transcribe(MEETING, new Audio(new byte[0], "m.txt"));

        assertThat(meetings.chunks).as("덩이가 덧붙지 않았다").hasSize(1);
    }

    // ── ③ 로컬키가 새 후보 ID 로 풀린다 ──────────────────────

    /**
     * 로컬키는 추출결과 안에서만 유효한 이름이라 저장 경계에서 한 번 해소한다.
     * 안 풀리는 참조는 무소속으로 남는다 — 의견을 버리지도, 이슈 후보를 지어내지도 않는다.
     */
    @Test
    void 참조_로컬키는_새_후보ID_로_풀리고_무소속은_null_로_남는다() {
        var meetings = new StubMeetings();
        meetings.saveTranscript(MEETING, utterances("a", "b", "c"));
        var extractions = new StubExtractions();

        orchestrator(meetings, extractions, port(new ExtractionResult(META,
                List.of(new ExtractedCandidate(new LocalKey("proxy"), 쟁점("무엇으로 할 것인가"), List.of(0))),
                List.of(new ExtractedOpinion(new LocalKey("proxy"), new Opinion.Content(null, "nginx가 익숙하다"), 1),
                        new ExtractedOpinion(null, new Opinion.Content(null, "오늘은 컨디션이 안 좋다"), 2)),
                List.of(new ExtractedTask(new LocalKey("ghost"), new Task.Content("명세 조사", null), 2)),
                List.of())))
                .extract(MEETING, "1");

        var candidateId = extractions.saved.issueCandidates().getFirst().id();
        assertThat(candidateId.value()).as("로컬키가 아니라 새로 만든 ID 다").isNotEqualTo("proxy");
        assertThat(extractions.saved.opinions()).extracting(Opinion::issue)
                .containsExactly(candidateId, null);
        assertThat(extractions.saved.tasks().getFirst().issue())
                .as("안 풀리는 참조는 무소속이다 — 개수가 안 준다").isNull();
        assertThat(extractions.saved.opinions()).hasSize(2);
    }

    // ── 손 스텁 ─────────────────────────────────────────────

    private RoundOrchestrator orchestrator(MeetingStore meetings, ExtractionStore extractions, ExtractPort extract) {
        return new RoundOrchestrator(THROWING_STT, extract, meetings, extractions,
                STUB_ISSUES, STUB_GLOSSARY, DIRECT_UNIT);
    }

    static final class StubMeetings implements MeetingStore {
        final List<Transcript> chunks = new ArrayList<>();

        @Override public MeetingId newMeeting() { return MEETING; }

        @Override public TranscriptId saveTranscript(MeetingId meeting, List<Utterance> transcript) {
            var id = new TranscriptId("t" + chunks.size());
            chunks.add(new Transcript(id, transcript));
            return id;
        }

        @Override public List<Transcript> fullTranscript(MeetingId meeting) { return List.copyOf(chunks); }
    }

    static final class StubExtractions implements ExtractionStore {
        Extraction saved;

        @Override public void save(Extraction extraction) { saved = extraction; }
        @Override public List<IssueCandidate> unconfirmedCandidates(MeetingId meeting) { return List.of(); }
        @Override public List<TermCandidate> termCandidates(MeetingId meeting) { return List.of(); }
    }

    /** 불리면 던진다 — "전사를 건너뛴다"는 안 부른다는 뜻이지 싸게 부른다는 뜻이 아니다. */
    private static final SttPort THROWING_STT = new SttPort() {
        @Override public JobId requestTranscription(Audio audio, List<ContextItem> priorityOrdered) {
            throw new AssertionError("STT 를 불렀다 — 회의록이 이미 있으면 전사를 건너뛰어야 한다");
        }
        @Override public JobStatus jobStatus(JobId id) { throw new AssertionError(); }
        @Override public TranscriptionResult result(JobId id) { throw new AssertionError(); }
    };

    private static final IssueStore STUB_ISSUES = new IssueStore() {
        @Override public void promote(List<CandidateId> ids) {}
        @Override public List<Issue> all() { return List.of(); }
        @Override public Optional<Issue> find(IssueId id) { return Optional.empty(); }
    };

    private static final GlossaryStore STUB_GLOSSARY = new GlossaryStore() {
        @Override public void add(List<Term> terms) {}
        @Override public List<Term> all() { return List.of(); }
        @Override public void edit(String oldSpelling, String newSpelling, String newMeaning) {}
    };

    private static final UnitOfWork DIRECT_UNIT = new UnitOfWork() {
        @Override public <T> T within(Supplier<T> block) { return block.get(); }
    };

    private static ExtractPort port(ExtractionResult result) {
        return (transcript, promptVersion) -> result;
    }

    private static ExtractionResult empty() {
        return new ExtractionResult(META, List.of(), List.of(), List.of(), List.of());
    }

    private static IssueCandidate.Content 쟁점(String question) {
        return IssueCandidate.Content.lenient(question, ProposedState.쟁점, null, null);
    }

    private static List<Utterance> utterances(String... texts) {
        var out = new ArrayList<Utterance>();
        for (int i = 0; i < texts.length; i++) out.add(new Utterance(null, i * 3.0, i * 3.0 + 3, texts[i]));
        return out;
    }
}
