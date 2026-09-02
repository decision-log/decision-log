package dl.sim;

import dl.adapters.extract.MarkerExtractAdapter;
import dl.adapters.extract.MarkerExtractAdapter.FailureMode;
import dl.adapters.store.InMemory;
import dl.adapters.stt.SimulatorSttAdapter;
import dl.adapters.stt.SimulatorSttAdapter.Mode;
import dl.adapters.stt.SimulatorSttAdapter.Rule;
import dl.adapters.stt.MeasuredCorruptionRules;
import dl.domain.RoundOrchestrator;
import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Audio;
import dl.script.Scripts;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 회차를 돈다. 사슬 로직은 전부 domain 의 오케스트레이터가 갖는다. */
public final class RoundSimulator {

    /**
     * ADR 0005 가 그림으로 든 예시 규칙이다 — <b>실측이 아니다.</b>
     * 용어집에 {@code Caddy} 가 올라간 뒤 "응답 캐시"가 "응답 Caddy"로 끌려가는 것,
     * 즉 컨텍스트 주입의 <i>대가</i>를 눈으로 보려고 여기서 만든다.
     * 실측 목록({@link MeasuredCorruptionRules})에는 넣지 않는다.
     */
    static final Rule CADDY = new Rule("Caddy", Mode.STEADY, List.of("캐디"), List.of("캐시"));

    /**
     * 인자로 켜는 실패 모드. 이름은 부모 이슈가 쓰는 말이고 값은 어댑터의 스위치다.
     *
     * <p>스위치를 만들어 놓고 켤 길이 없으면 <i>"작문오염 모드로 3회차를 돌리면 컨텍스트가
     * 얼마나 나빠지나를 몇 초에 묻는다"</i> 가 반쪽이 된다.
     */
    private static final Map<String, FailureMode> SWITCHES = new LinkedHashMap<>();
    static {
        SWITCHES.put("과잉분할", FailureMode.OVERSPLIT);
        SWITCHES.put("작문오염", FailureMode.FABRICATE);
        SWITCHES.put("누락", FailureMode.OMIT);
        SWITCHES.put("담당자없음", FailureMode.NO_ASSIGNEE);
    }

    public static void main(String[] args) {
        var failureModes = EnumSet.noneOf(FailureMode.class);
        for (String arg : args) {
            var mode = SWITCHES.get(arg);
            if (mode == null) {
                System.out.println("모르는 스위치입니다: " + arg);
                System.out.println("사용법: RoundSimulator [" + String.join(" | ", SWITCHES.keySet()) + "] …");
                return;
            }
            failureModes.add(mode);
        }

        var rules = new ArrayList<>(MeasuredCorruptionRules.all());
        rules.add(CADDY);

        // 확인 화면에서 사람이 하는 일: 깨진 표기를 정답으로 고친다
        var humanCorrections = new HashMap<String, String>();
        for (Rule r : rules) for (String broken : r.corruptions()) humanCorrections.put(broken, r.correct());

        var meetings = new InMemory.Meetings();
        var extractions = new InMemory.Extractions();
        var issues = new InMemory.Issues(extractions);
        var glossary = new InMemory.Glossary();

        long t0 = System.nanoTime();
        for (int round = 1; round <= 3; round++) {
            var stt = new SimulatorSttAdapter(rules, 7L);
            var o = new RoundOrchestrator(stt, new MarkerExtractAdapter(failureModes), meetings, extractions,
                    issues, glossary, new InMemory.Unit(meetings, issues, glossary, extractions));

            // 회의를 만드는 것은 호출자의 일이다 — 애플리케이션에서는 사람이 먼저 만든다
            var r = o.run(meetings.newMeeting(), asScript(Scripts.defaultScript()), "1");

            System.out.println("── " + round + "회차  (용어집 " + glossary.all().size()
                    + "건 → 컨텍스트 " + o.assembleContext().size() + "항목)");
            r.transcript().forEach(u -> System.out.println("     " + u.text()));
            System.out.println("   이슈후보: " + r.extraction().issueCandidates().stream()
                    .map(c -> c.content().question()).toList());
            System.out.println("   용어후보: " + r.extraction().termCandidates().stream()
                    .map(t -> t.content().spelling()).toList());

            // 확인 — 후보 전량 승격, 용어는 표기를 고쳐서 승격 (교정은 호출자의 일이다)
            var correctedTerms = r.extraction().termCandidates().stream()
                    .map(t -> new Term(humanCorrections.getOrDefault(t.content().spelling(), t.content().spelling()),
                            t.content().meaning()))
                    .toList();
            o.confirm(r.extraction().issueCandidates().stream().map(IssueCandidate::id).toList(), correctedTerms);
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
