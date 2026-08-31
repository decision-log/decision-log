package dl.sim;

import dl.adapters.extract.MarkerExtractAdapter;
import dl.adapters.store.InMemory;
import dl.adapters.stt.SimulatorSttAdapter;
import dl.adapters.stt.SimulatorSttAdapter.Mode;
import dl.adapters.stt.SimulatorSttAdapter.Rule;
import dl.adapters.stt.MeasuredCorruptionRules;
import dl.domain.RoundOrchestrator;
import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Audio;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 회차를 돈다. 사슬 로직은 전부 domain 의 오케스트레이터가 갖는다. */
public final class RoundSimulator {

    /**
     * ADR 0005 가 그림으로 든 예시 규칙이다 — <b>실측이 아니다.</b>
     * 용어집에 {@code Caddy} 가 올라간 뒤 "응답 캐시"가 "응답 Caddy"로 끌려가는 것,
     * 즉 컨텍스트 주입의 <i>대가</i>를 눈으로 보려고 여기서 만든다.
     * 실측 목록({@link 실측오염규칙})에는 넣지 않는다.
     */
    static final Rule CADDY = new Rule("Caddy", Mode.STEADY, List.of("캐디"), List.of("캐시"));

    public static void main(String[] args) {
        var script = String.join("\n", List.of(
                "리버스 프록시는 «용어:Caddy»로 가죠, «이슈:리버스 프록시를 무엇으로 할 것인가»",
                "응답 캐시를 걸어두면 빨라집니다",
                "«용어:툴 콜링»을 어떻게 처리할지 «이슈:툴 콜링 실패를 어떻게 처리할 것인가»",
                "«용어:스크럼»은 매일 아침에 합니다"));

        var rules = new ArrayList<>(MeasuredCorruptionRules.all());
        rules.add(CADDY);

        // 확인 화면에서 사람이 하는 일: 깨진 표기를 정답으로 고친다
        var humanCorrections = new HashMap<String, String>();
        for (Rule r : rules) for (String broken : r.corruptions()) humanCorrections.put(broken, r.correct());

        var meetings = new InMemory.Meetings();
        var issues = new InMemory.Issues();
        var glossary = new InMemory.Glossary();

        long t0 = System.nanoTime();
        for (int round = 1; round <= 3; round++) {
            var stt = new SimulatorSttAdapter(rules, 7L);
            var o = new RoundOrchestrator(stt, new MarkerExtractAdapter(), meetings, issues, glossary,
                    new InMemory.Unit(meetings, issues, glossary));

            // 회의를 만드는 것은 호출자의 일이다 — 애플리케이션에서는 사람이 먼저 만든다
            var r = o.run(meetings.newMeeting(), asScript(script), "1");

            System.out.println("── " + round + "회차  (용어집 " + glossary.all().size()
                    + "건 → 컨텍스트 " + o.assembleContext().size() + "항목)");
            r.transcript().forEach(u -> System.out.println("     " + u.text()));
            System.out.println("   이슈후보: " + r.candidates().stream().map(IssueCandidate::question).toList());
            System.out.println("   용어후보: " + r.termCandidates().stream().map(TermCandidate::spelling).toList());

            // 확인 — 후보 전량 승격, 용어는 표기를 고쳐서 승격
            var correctedTerms = r.termCandidates().stream()
                    .map(t -> new TermCandidate(humanCorrections.getOrDefault(t.spelling(), t.spelling()), t.meaning(), t.meeting()))
                    .toList();
            o.confirm(r.candidates().stream().map(IssueCandidate::id).toList(), correctedTerms);
            System.out.println("   확인 후 용어집: " + glossary.all().stream().map(Term::spelling).toList());
            System.out.println();
        }
        System.out.printf("3회차 %d ms%n", (System.nanoTime() - t0) / 1_000_000);
    }

    /** 가짜 STT 는 오디오가 아니라 대본을 받는다 (ADR 0005) — 그 자리가 {@link Audio} 다. */
    private static Audio asScript(String script) {
        return new Audio(script.getBytes(StandardCharsets.UTF_8), "meeting.txt");
    }
}
