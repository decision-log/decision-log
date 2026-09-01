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

    public enum Mode { 일관, 흔들림 }

    /**
     * 오염 규칙 하나.
     *
     * @param 정답       회의에서 실제로 나온 말
     * @param mode       일관은 한 회차 안에서 같은 형태로, 흔들림은 등장마다 다른 형태로 깨진다
     * @param 오염형      정답이 컨텍스트에 없을 때 깨지는 형태들
     * @param 끌려오는말  정답이 컨텍스트에 있을 때 정답 쪽으로 끌려가는 엉뚱한 말들
     */
    public record Rule(String 정답, Mode mode, List<String> 오염형, List<String> 끌려오는말) {
        public Rule {
            if (정답 == null || 정답.isEmpty()) throw new IllegalArgumentException("정답이 비었다");
            오염형 = List.copyOf(오염형);
            끌려오는말 = List.copyOf(끌려오는말);
        }

        /** 실측이 정방향만 준 규칙 — 역방향은 비어 있다. */
        public Rule(String 정답, Mode mode, List<String> 오염형) {
            this(정답, mode, 오염형, List.of());
        }
    }

    /**
     * 추측치다. 진짜 어댑터로 재본 적이 없다 — 이 값이 얼마인지는
     * stt-requirements.md 의 공급자 평가 절차가 답한다. 그때까지 이 상수에 기대 판단하지 않는다.
     */
    private static final int PROMPT_CHAR_BUDGET = 600;

    private static final double 발화길이초 = 3;

    private final List<Rule> rules;
    private final long seed;
    private final Map<String, TranscriptionResult> done = new HashMap<>();

    public SimulatorSttAdapter(List<Rule> rules, long seed) {
        // 정답이 긴 것부터 — ".git" 이 먼저 걸리면 ".gitignore" 는 "점기ignore" 가 되어
        // 자기 규칙에 영영 안 걸린다. 실측 규칙 넷 중 둘이 접두사 관계다.
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt((Rule r) -> r.정답().length()).reversed())
                .toList();
        this.seed = seed;
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

        // 호출마다 시드를 다시 건다 — 같은 대본 · 같은 컨텍스트면 몇 번을 불러도 같은 회의록이다
        var rng = new Random(seed);
        var utterances = new ArrayList<Utterance>();
        double t = 0;
        for (String line : 대본(audio)) {
            // 화자 라벨은 채우지 않는다. 화자 분리를 하지 않으므로 채우면 거짓말이다 (seams.md ⓵)
            utterances.add(new Utterance(null, t, t + 발화길이초, 오염시킨다(line, known, rng)));
            t += 발화길이초;
        }

        var id = UUID.randomUUID().toString();
        done.put(id, new TranscriptionResult(utterances, kept, dropped, List.of("{\"simulated\":true}")));
        return new JobId(id);
    }

    @Override public JobStatus 작업상태(JobId id) { return new JobStatus.Done(); }
    @Override public TranscriptionResult 결과(JobId id) { return done.get(id.value()); }

    // ── 대본 ────────────────────────────────────────────────

    /** 올린 파일이 곧 대본이다 — 줄 하나가 발화 하나 (ADR 0005: "가짜 STT는 대본을 받는다"). */
    private static List<String> 대본(Audio audio) {
        var 해독기 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String 텍스트;
        try {
            텍스트 = 해독기.decode(ByteBuffer.wrap(audio.bytes())).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException(
                    "시뮬레이터 STT 는 대본(UTF-8 텍스트)을 받는다 — '%s' 은 텍스트가 아니다"
                            .formatted(audio.filename()));
        }
        return 텍스트.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    // ── 오염 ────────────────────────────────────────────────

    /** 규칙 하나당 방향은 하나다 — 컨텍스트에 있거나 없거나 둘 중 하나다. */
    private String 오염시킨다(String 줄, Set<String> known, Random rng) {
        var out = 줄;
        for (Rule r : rules)
            out = known.contains(r.정답()) ? 끌어당긴다(out, r) : 깨뜨린다(out, r, rng);
        return out;
    }

    /**
     * 정답이 컨텍스트에 없다 — 규칙대로 깨진다.
     *
     * <p>흔들림은 <b>등장마다</b> 다시 뽑는다. 줄 단위로 한 번만 뽑으면 한 줄에 두 번 나온
     * 말이 같은 형태로 깨져 <i>"같은 말인지도 모른다"</i> 는 흔들림의 성질이 사라진다.
     */
    private static String 깨뜨린다(String 줄, Rule r, Random rng) {
        if (r.오염형().isEmpty()) return 줄;
        var out = new StringBuilder();
        int 읽은데까지 = 0;
        for (int 찾은자리; (찾은자리 = 줄.indexOf(r.정답(), 읽은데까지)) >= 0; ) {
            out.append(줄, 읽은데까지, 찾은자리).append(한형태(r, rng));
            읽은데까지 = 찾은자리 + r.정답().length();
        }
        return out.append(줄, 읽은데까지, 줄.length()).toString();
    }

    private static String 한형태(Rule r, Random rng) {
        return r.mode() == Mode.일관 ? r.오염형().get(0) : r.오염형().get(rng.nextInt(r.오염형().size()));
    }

    /**
     * 정답이 컨텍스트에 있다 — 엉뚱한 말이 그쪽으로 끌려간다.
     * ADR 0005 의 {@code 응답 캐시 → 응답 Caddy} 가 이 방향이다. 주입의 대가라 무작위가 아니다.
     */
    private static String 끌어당긴다(String 줄, Rule r) {
        var out = 줄;
        for (String 끌려오는말 : r.끌려오는말()) out = out.replace(끌려오는말, r.정답());
        return out;
    }
}
