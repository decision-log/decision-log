package dl.domain;

import dl.domain.model.Model.*;
import dl.domain.ports.*;
import dl.domain.ports.SttPort.*;
import dl.domain.ports.Stores.*;

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
    private final IssueStore issues;
    private final GlossaryStore glossary;
    private final UnitOfWork unit;

    public RoundOrchestrator(SttPort stt, ExtractPort extract,
                              MeetingStore meetings, IssueStore issues, GlossaryStore glossary, UnitOfWork unit) {
        this.stt = stt; this.extract = extract;
        this.meetings = meetings; this.issues = issues; this.glossary = glossary; this.unit = unit;
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

    public record Transcription(MeetingId meeting, List<Utterance> transcript) {}

    public record RoundResult(MeetingId meeting, List<Utterance> transcript, List<IssueCandidate> candidates, List<TermCandidate> termCandidates) {}

    /**
     * 오디오 하나가 회의록과 후보가 된다.
     *
     * 전사와 추출이 각각 수 분 걸리므로 저장은 두 섬으로 갈린다.
     * 긴 외부 호출을 트랜잭션 안에 두면 커넥션을 그동안 붙잡게 된다.
     *
     * <p>두 섬을 밖에서도 따로 부를 수 있게 열어 둔다 — 잡이 그 사이에서 진행을 보고한다.
     */
    public RoundResult run(MeetingId meeting, Audio audio, String promptVersion) {
        return extract(transcribe(meeting, audio), promptVersion);
    }

    /**
     * 오디오가 이 회의의 회의록이 된다.
     *
     * <p><b>회의를 만드는 것은 호출자의 일이다.</b> 사람이 회의를 먼저 만들고 오디오를 올리므로
     * 여기서 또 만들면 회의가 둘이 된다 (#3 합의문 "미룬 충돌").
     *
     * <p>회의록이 비면 실패다. 조용히 빈 결과를 받지 않는 것이 부모 이슈 사용자 스토리 9번이다.
     */
    public Transcription transcribe(MeetingId meeting, Audio audio) {
        var ctx = assembleContext();
        var job = stt.requestTranscription(audio, ctx);
        var transcription = stt.result(job);
        if (transcription.transcript().isEmpty())
            throw new IllegalStateException("전사 결과가 비었다 — 회의록이 한 줄도 없다");

        unit.within(() -> meetings.saveTranscript(meeting, transcription.transcript()));
        return new Transcription(meeting, transcription.transcript());
    }

    /** 회의록이 후보가 된다. */
    public RoundResult extract(Transcription transcription, String promptVersion) {
        var meeting = transcription.meeting();
        var extraction = extract.extract(transcription.transcript(), promptVersion);

        var candidates = extraction.issueCandidates().stream()
                .map(d -> new IssueCandidate(new IssueId(UUID.randomUUID().toString()), meeting,
                                       d.question(), d.state(), d.answer(), d.evidenceSpans()))
                .toList();
        unit.within(() -> issues.saveCandidates(candidates));

        return new RoundResult(meeting, transcription.transcript(), candidates, extraction.termCandidates());
    }

    /**
     * 확인 — 후보를 이슈로, 용어 후보를 용어집으로. 회의 참석자가 함께 한다.
     *
     * 둘은 한 단위다. 절반만 확인된 회의가 남으면 다음 회차 컨텍스트로 그대로 나간다.
     */
    public void confirm(List<IssueId> candidatesToPromote, List<TermCandidate> termsToPromote) {
        var terms = dedupeBySpelling(termsToPromote);
        unit.within(() -> {
            issues.promote(candidatesToPromote);
            glossary.add(terms);
        });
    }

    /** 같은 표기가 한 회의에서 여러 번 나올 수 있다. 먼저 나온 것을 남긴다. */
    private static List<Term> dedupeBySpelling(List<TermCandidate> candidate) {
        var m = new LinkedHashMap<String, Term>();
        for (var t : candidate) m.putIfAbsent(t.spelling(), new Term(t.spelling(), t.meaning()));
        return List.copyOf(m.values());
    }
}
