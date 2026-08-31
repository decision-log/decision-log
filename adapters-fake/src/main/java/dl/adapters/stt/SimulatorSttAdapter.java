package dl.adapters.stt;

import dl.domain.ports.SttPort;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 대본 + 오염 규칙 (ADR 0005). 재생이 아니다 — 검증하려는 주장이
 * <i>"컨텍스트가 바뀌면 전사가 달라진다"</i> 라서 같은 입력에 같은 출력을 주면 소용이 없다.
 *
 * <p><b>대본은 {@link Audio} 로 들어온다.</b> 올린 파일이 곧 대본이고 줄 하나가 발화 하나다.
 * 생성자로 받으면 어댑터 하나가 대본 하나에 묶여, 애플리케이션에 꽂았을 때 어느 회의를 올리든
 * 같은 회의록이 나온다. 텍스트가 아니면 조용히 쓰레기를 만들지 않고 실패한다.
 *
 * <p>규칙 하나가 방향 둘을 갖는다. 컨텍스트에 정답이 <b>없으면</b> 정답이 오염형으로 깨지고
 * (고착), <b>있으면</b> 끌려오는말이 정답으로 끌려간다 (주입의 대가). 대가를 빼면 사슬이
 * 실제보다 좋아 보인다 — 공급자 평가 절차 3번이 재는 값이다 (stt-requirements.md).
 */
public final class SimulatorSttAdapter implements SttPort {

    public enum Mode { STEADY, WOBBLY }

    /**
     * 오염 규칙 하나.
     *
     * @param 정답       회의에서 실제로 나온 말
     * @param mode       일관은 한 회차 안에서 같은 형태로, 흔들림은 등장마다 다른 형태로 깨진다
     * @param 오염형      정답이 컨텍스트에 없을 때 깨지는 형태들
     * @param 끌려오는말  정답이 컨텍스트에 있을 때 정답 쪽으로 끌려가는 엉뚱한 말들
     */
    public record Rule(String correct, Mode mode, List<String> corruptions, List<String> pulledWords) {
        public Rule {
            if (correct == null || correct.isEmpty()) throw new IllegalArgumentException("정답이 비었다");
            corruptions = List.copyOf(corruptions);
            pulledWords = List.copyOf(pulledWords);
        }

        /** 실측이 정방향만 준 규칙 — 역방향은 비어 있다. */
        public Rule(String correct, Mode mode, List<String> corruptions) {
            this(correct, mode, corruptions, List.of());
        }
    }

    /**
     * 추측치다. 진짜 어댑터로 재본 적이 없다 — 이 값이 얼마인지는
     * stt-requirements.md 의 공급자 평가 절차가 답한다. 그때까지 이 상수에 기대 판단하지 않는다.
     */
    private static final int PROMPT_CHAR_BUDGET = 600;

    private static final double UTTERANCE_SECONDS = 3;

    private final List<Rule> rules;
    private final long seed;
    private final Map<String, TranscriptionResult> done = new HashMap<>();

    public SimulatorSttAdapter(List<Rule> rules, long seed) {
        // 정답이 긴 것부터 — ".git" 이 먼저 걸리면 ".gitignore" 는 "점기ignore" 가 되어
        // 자기 규칙에 영영 안 걸린다. 실측 규칙 넷 중 둘이 접두사 관계다.
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt((Rule r) -> r.correct().length()).reversed())
                .toList();
        this.seed = seed;
    }

    @Override
    public JobId requestTranscription(Audio audio, List<ContextItem> priorityOrdered) {
        var kept = new ArrayList<ContextItem>();
        var dropped = new ArrayList<ContextItem>();
        int used = 0;
        for (var item : priorityOrdered) {
            int len = item.spelling().length() + (item.meaning() == null ? 0 : item.meaning().length() + 2);
            if (used + len <= PROMPT_CHAR_BUDGET) { kept.add(item); used += len; }
            else dropped.add(item);
        }
        var known = new HashSet<String>();
        for (var i : kept) known.add(i.spelling());

        // 호출마다 시드를 다시 건다 — 같은 대본 · 같은 컨텍스트면 몇 번을 불러도 같은 회의록이다
        var rng = new Random(seed);
        var utterances = new ArrayList<Utterance>();
        double t = 0;
        for (String line : script(audio)) {
            // 화자 라벨은 채우지 않는다. 화자 분리를 하지 않으므로 채우면 거짓말이다 (seams.md ⓵)
            utterances.add(new Utterance(null, t, t + UTTERANCE_SECONDS, corrupt(line, known, rng)));
            t += UTTERANCE_SECONDS;
        }

        var id = UUID.randomUUID().toString();
        done.put(id, new TranscriptionResult(utterances, kept, dropped, List.of("{\"simulated\":true}")));
        return new JobId(id);
    }

    @Override public JobStatus jobStatus(JobId id) { return new JobStatus.Done(); }
    @Override public TranscriptionResult result(JobId id) { return done.get(id.value()); }

    // ── 대본 ────────────────────────────────────────────────

    /** 올린 파일이 곧 대본이다 — 줄 하나가 발화 하나 (ADR 0005: "가짜 STT는 대본을 받는다"). */
    private static List<String> script(Audio audio) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try {
            text = decoder.decode(ByteBuffer.wrap(audio.bytes())).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "시뮬레이터 STT 는 대본(UTF-8 텍스트)을 받는다 — '%s' 은 텍스트가 아니다"
                            .formatted(audio.filename()));
        }
        return text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    // ── 오염 ────────────────────────────────────────────────

    /** 규칙 하나당 방향은 하나다 — 컨텍스트에 있거나 없거나 둘 중 하나다. */
    private String corrupt(String line, Set<String> known, Random rng) {
        var out = line;
        for (Rule r : rules)
            out = known.contains(r.correct()) ? pullToward(out, r) : breakWord(out, r, rng);
        return out;
    }

    /**
     * 정답이 컨텍스트에 없다 — 규칙대로 깨진다.
     *
     * <p>흔들림은 <b>등장마다</b> 다시 뽑는다. 줄 단위로 한 번만 뽑으면 한 줄에 두 번 나온
     * 말이 같은 형태로 깨져 <i>"같은 말인지도 모른다"</i> 는 흔들림의 성질이 사라진다.
     */
    private static String breakWord(String line, Rule r, Random rng) {
        if (r.corruptions().isEmpty()) return line;
        var out = new StringBuilder();
        int readUpTo = 0;
        for (int hit; (hit = line.indexOf(r.correct(), readUpTo)) >= 0; ) {
            out.append(line, readUpTo, hit).append(oneForm(r, rng));
            readUpTo = hit + r.correct().length();
        }
        return out.append(line, readUpTo, line.length()).toString();
    }

    private static String oneForm(Rule r, Random rng) {
        return r.mode() == Mode.STEADY ? r.corruptions().get(0) : r.corruptions().get(rng.nextInt(r.corruptions().size()));
    }

    /**
     * 정답이 컨텍스트에 있다 — 엉뚱한 말이 그쪽으로 끌려간다.
     * ADR 0005 의 {@code 응답 캐시 → 응답 Caddy} 가 이 방향이다. 주입의 대가라 무작위가 아니다.
     */
    private static String pullToward(String line, Rule r) {
        var out = line;
        for (String pulledWords : r.pulledWords()) out = out.replace(pulledWords, r.correct());
        return out;
    }
}
