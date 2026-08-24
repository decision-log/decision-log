package dl.adapters.stt;

import dl.domain.ports.SttPort;

import java.util.*;

/** ADR 0005 — 대본 + 오염 규칙. 컨텍스트에 있는 용어는 정확히, 없으면 규칙대로 깨진다. */
public final class SimulatorSttAdapter implements SttPort {

    public enum Mode { 일관, 흔들림 }

    public record Rule(String 정답, Mode mode, List<String> 오염형) {}

    /**
     * 추측치다. 진짜 어댑터로 재본 적이 없다 — 이 값이 얼마인지는
     * stt-requirements.md 의 공급자 평가 절차가 답한다. 그때까지 이 상수에 기대 판단하지 않는다.
     */
    private static final int PROMPT_CHAR_BUDGET = 600;

    private final List<String> script;
    private final List<Rule> rules;
    private final Random rng;
    private final Map<String, TranscriptionResult> done = new HashMap<>();

    public SimulatorSttAdapter(List<String> script, List<Rule> rules, long seed) {
        this.script = script; this.rules = rules; this.rng = new Random(seed);
    }

    @Override
    public JobId 전사요청(Audio audio, List<ContextItem> priorityOrdered) {
        var kept = new ArrayList<ContextItem>();
        var dropped = new ArrayList<ContextItem>();
        int used = 0;
        for (var item : priorityOrdered) {
            int len = item.표기().length() + (item.뜻() == null ? 0 : item.뜻().length() + 2);
            if (used + len <= PROMPT_CHAR_BUDGET) { kept.add(item); used += len; }
            else dropped.add(item);
        }
        var known = new HashSet<String>();
        for (var i : kept) known.add(i.표기());

        var utterances = new ArrayList<Utterance>();
        double t = 0;
        for (String line : script) {
            String out = line;
            for (Rule r : rules) {
                if (known.contains(r.정답())) continue;                  // 컨텍스트에 있으면 정확히
                String broken = switch (r.mode()) {
                    case 일관 -> r.오염형().get(0);
                    case 흔들림 -> r.오염형().get(rng.nextInt(r.오염형().size()));
                };
                out = out.replace(r.정답(), broken);
            }
            utterances.add(new Utterance("화자?", t, t + 3, out));
            t += 3;
        }
        var id = UUID.randomUUID().toString();
        done.put(id, new TranscriptionResult(utterances, kept, dropped, List.of("{\"simulated\":true}")));
        return new JobId(id);
    }

    @Override public JobStatus 작업상태(JobId id) { return new JobStatus.Done(); }
    @Override public TranscriptionResult 결과(JobId id) { return done.get(id.value()); }
}
