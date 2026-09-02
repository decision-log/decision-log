package dl.domain;

import dl.domain.model.Model.*;
import dl.domain.ports.*;
import dl.domain.ports.SttPort.*;
import dl.domain.ports.Stores.*;

import java.time.Instant;
import java.util.*;

/**
 * 한 회차가 도는 순서. 회차 시뮬레이터와 애플리케이션이 **같은 것을** 돈다.
 *
 * 프레임워크를 모른다. 트랜잭션도 모른다 — 아는 것은 "여기부터 여기까지가 한 단위"뿐이고,
 * 그걸 {@link UnitOfWork} 에 말한다.
 */
public final class RoundOrchestrator {

    private final SttPort stt;
    private final ExtractPort extract;
    private final MeetingStore meetings;
    private final ExtractionStore extractions;
    private final IssueStore issues;
    private final GlossaryStore glossary;
    private final UnitOfWork unit;

    public RoundOrchestrator(SttPort stt, ExtractPort extract, MeetingStore meetings, ExtractionStore extractions,
                              IssueStore issues, GlossaryStore glossary, UnitOfWork unit) {
        this.stt = stt; this.extract = extract;
        this.meetings = meetings; this.extractions = extractions;
        this.issues = issues; this.glossary = glossary; this.unit = unit;
    }

    /**
     * 우선순위 순으로 전량을 넘긴다. 한도까지 자르는 것은 어댑터의 일이다 (seams.md ⓵).
     */
    public List<ContextItem> assembleContext() {
        var allIssues = issues.all();
        var out = new ArrayList<ContextItem>();

        for (var t : glossary.all())
            out.add(new ContextItem(ContextKind.TERM, t.spelling(), t.meaning()));
        for (var i : allIssues)
            if (i.state() == State.쟁점) out.add(new ContextItem(ContextKind.OPEN_CANDIDATE, i.question(), null));
        for (var i : allIssues)
            if (i.state() != State.쟁점 && i.state() != State.무효)
                out.add(new ContextItem(ContextKind.ISSUE, i.question(), i.answer()));
        return out;
    }

    public record RoundResult(MeetingId meeting, List<Utterance> transcript, Extraction extraction) {}

    /**
     * 오디오 하나가 회의록과 후보가 된다.
     *
     * 전사와 추출이 각각 수 분 걸리므로 저장은 두 섬으로 갈린다.
     * 긴 외부 호출을 트랜잭션 안에 두면 커넥션을 그동안 붙잡게 된다.
     *
     * <p>두 섬을 밖에서도 따로 부를 수 있게 열어 둔다 — 잡이 그 사이에서 진행을 보고한다.
     */
    public RoundResult run(MeetingId meeting, Audio audio, String promptVersion) {
        transcribe(meeting, audio);
        return extract(meeting, promptVersion);
    }

    /**
     * 오디오가 이 회의의 회의록이 된다.
     *
     * <p><b>회의를 만드는 것은 호출자의 일이다.</b> 사람이 회의를 먼저 만들고 오디오를 올리므로
     * 여기서 또 만들면 회의가 둘이 된다 (#3 합의문 "미룬 충돌").
     *
     * <p><b>회의록이 이미 있으면 전사를 건너뛴다.</b> 잡 재시도가 같은 잡 행을 리셋해 처음부터
     * 다시 돌리는데, 회의록저장은 덧붙이기라 회의가 두 벌이 되고 추출이 합쳐서 한 번 도니
     * 이슈가 정확히 두 배로 뽑힌다 — 그리고 에러가 안 난다.
     * 잡 재시도와 재처리는 다른 연산이다: <i>"같은 오디오로 공급자를 바꿔 다시"</i> 는 #18 이
     * 만들 명시적 버튼이고, 덩이를 덧붙이는 경로는 #7+ 가 자기 연산으로 만든다.
     *
     * <p>회의록이 비면 실패다. 조용히 빈 결과를 받지 않는 것이 부모 이슈 사용자 스토리 9번이다.
     */
    public void transcribe(MeetingId meeting, Audio audio) {
        if (!meetings.fullTranscript(meeting).isEmpty()) return;

        var ctx = assembleContext();
        var job = stt.requestTranscription(audio, ctx);
        var transcription = stt.result(job);
        if (transcription.transcript().isEmpty())
            throw new IllegalStateException("전사 결과가 비었다 — 회의록이 한 줄도 없다");

        unit.within(() -> { meetings.saveTranscript(meeting, transcription.transcript()); });
    }

    /**
     * 회의록이 후보가 된다. <b>추출은 회의에 붙는다</b> — 회의록이 여러 덩이여도 전체를 합쳐
     * 한 번 돈다. 이어붙인 순서가 곧 어댑터가 보는 전역 구간 번호다.
     *
     * <p>어댑터가 만든 참조를 여기서 해소한다 — 전역 번호는 {@code (회의록, 순번)} 으로,
     * 로컬키는 새 후보 ID 로. <b>원시 참조는 DB 에 안 남는다.</b> 남기면 후보 하나를 버릴 때,
     * 제목을 고칠 때, 두 번째 추출이 돌 때 조용히 어긋나고 실패 모드가 미아가 아니라
     * 오귀속이라 아무 데도 안 걸린다 (seams.md ⓶).
     */
    public RoundResult extract(MeetingId meeting, String promptVersion) {
        var chunks = meetings.fullTranscript(meeting);
        if (chunks.isEmpty()) throw new IllegalStateException("회의록이 없는 회의는 추출할 수 없다");

        // [t0#0 t0#1 t0#2 | t1#0 t1#1] — 이어붙인 자리가 전역 번호이자 근거 해소표의 색인이다
        var joined = new ArrayList<Utterance>();
        var evidenceBySpan = new ArrayList<Evidence>();
        for (var chunk : chunks) {
            for (int seq = 0; seq < chunk.utterances().size(); seq++) {
                joined.add(chunk.utterances().get(seq));
                evidenceBySpan.add(new Evidence(chunk.id(), seq));
            }
        }
        var transcript = List.copyOf(joined);
        var result = extract.extract(transcript, promptVersion);

        var extractionId = new ExtractionId(UUID.randomUUID().toString());
        var idByKey = new LinkedHashMap<ExtractPort.LocalKey, CandidateId>();
        var candidates = new ArrayList<IssueCandidate>();
        for (var c : result.issueCandidates()) {
            var id = new CandidateId(UUID.randomUUID().toString());
            idByKey.put(c.key(), id);
            candidates.add(new IssueCandidate(id, extractionId, c.content(), evidence(c.spans(), evidenceBySpan)));
        }

        // 매핑에 없는 참조(null 포함)는 무소속으로 남는다 — 버리지도 지어내지도 않으므로 개수가 안 준다
        var opinions = result.opinions().stream()
                .map(o -> new Opinion(new OpinionId(UUID.randomUUID().toString()), extractionId,
                        idByKey.get(o.issueRef()), o.content(), evidenceBySpan.get(o.span())))
                .toList();
        var tasks = result.tasks().stream()
                .map(t -> new Task(new TaskId(UUID.randomUUID().toString()), extractionId,
                        idByKey.get(t.issueRef()), t.content(), evidenceBySpan.get(t.span())))
                .toList();
        var termCandidates = result.termCandidates().stream()
                .map(t -> new TermCandidate(new TermCandidateId(UUID.randomUUID().toString()), extractionId,
                        t.content(), evidence(t.spans(), evidenceBySpan)))
                .toList();

        var extraction = new Extraction(extractionId, meeting, result.meta(), Instant.now(),
                candidates, opinions, tasks, termCandidates);
        unit.within(() -> extractions.save(extraction));

        return new RoundResult(meeting, transcript, extraction);
    }

    private static List<Evidence> evidence(List<Integer> spans, List<Evidence> bySpan) {
        return spans.stream().map(bySpan::get).toList();
    }

    /**
     * 확인 — 후보를 이슈로, 용어 후보를 용어집으로. 회의 참석자가 함께 한다.
     *
     * 둘은 한 단위다. 절반만 확인된 회의가 남으면 다음 회차 컨텍스트로 그대로 나간다.
     *
     * <p>용어를 후보가 아니라 {@link Term} 으로 받는 것은 <b>깨진 표기를 고치는 것이
     * 호출자(확인 화면 · 회차 시뮬레이터)의 일</b>이기 때문이다.
     */
    public void confirm(List<CandidateId> candidatesToPromote, List<Term> termsToPromote) {
        var terms = dedupeBySpelling(termsToPromote);
        unit.within(() -> {
            issues.promote(candidatesToPromote);
            glossary.add(terms);
        });
    }

    /** 같은 표기가 한 회의에서 여러 번 나올 수 있다. 먼저 나온 것을 남긴다. */
    private static List<Term> dedupeBySpelling(List<Term> terms) {
        var m = new LinkedHashMap<String, Term>();
        for (var t : terms) m.putIfAbsent(t.spelling(), t);
        return List.copyOf(m.values());
    }
}
