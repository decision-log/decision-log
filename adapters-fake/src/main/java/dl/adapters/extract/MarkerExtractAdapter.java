package dl.adapters.extract;

import dl.domain.model.Model.*;
import dl.domain.ports.ExtractPort;
import dl.domain.ports.SttPort.Utterance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * ADR 0005 — 마커 대본. 대본에 정답을 달아두고 그걸 읽는다. LLM 안 부른다.
 *
 * <p>문법은 발화 한 줄 안에서 {@code |} 로 갈린다 — 왼쪽이 사람이 말한 문장, 오른쪽이 정답이다.
 * <b>정답이 발화와 같은 줄에 있어야 한다:</b> 오염 시뮬레이터의 {@code line.replace()} 가
 * 마커 내부까지 쳐서 깨진 표기가 이슈 제목에 박히는데, 대본 아래에 정답 블록을 따로 두면
 * 그 사슬이 못 친다. 대본을 {@code text.lines()} 로 자르므로 줄바꿈도 못 쓴다.
 *
 * <pre>
 * 종류[@키][: 본문[= 둘째값]]
 * 이슈 · 답 · 사유 · 의견 · 할일 · 용어      ┆ 스위치 대상 지정: 분할 · 작문 · 누락 · 무담당
 * </pre>
 */
public final class MarkerExtractAdapter implements ExtractPort {

    /**
     * 실패 모드 스위치 넷 (ADR 0005 후기).
     *
     * <p><b>대상을 대본이 지정한다 — 확률이 아니다.</b> 확률이면 <i>"3회차 돌리면 컨텍스트가
     * 얼마나 나빠지나"</i> 에 매번 다른 답이 나와 이 추출기의 존재 이유가 깨진다.
     * 표식이 아니라 <b>마커 종류</b>인 것은 한 이슈가 두 스위치의 대상이어야 하기 때문이다 —
     * 각자 자기 둘째값을 갖는다.
     *
     * <p>전파(제목에 깨진 표기가 박히는 것)는 여기 없다. 오염 시뮬레이터가 이미 내고,
     * 더하면 사슬이 작동해도 안 하는 것과 같아 보인다.
     */
    public enum FailureMode {
        OVERSPLIT("분할"), FABRICATE("작문"), OMIT("누락"), NO_ASSIGNEE("무담당");

        private final String marker;

        FailureMode(String marker) { this.marker = marker; }

        public String marker() { return marker; }
    }

    private static final String ISSUE = "이슈", ANSWER = "답", REASON = "사유";
    private static final String OPINION = "의견", TASK = "할일", TERM = "용어";

    private final Set<FailureMode> switches;

    /** 애플리케이션에 꽂히는 것 — 스위치 없는 마커다. */
    public MarkerExtractAdapter() { this(Set.of()); }

    /**
     * <b>스위치는 생성자로만 켠다.</b> 애플리케이션 설정을 만들지 않는다 —
     * 회차 시뮬레이터와 테스트만 켜므로 소비자 없는 설정이 늘 뿐이다.
     */
    public MarkerExtractAdapter(Set<FailureMode> switches) {
        var copy = EnumSet.noneOf(FailureMode.class);
        copy.addAll(switches);
        this.switches = Collections.unmodifiableSet(copy);
    }

    @Override
    public ExtractionResult extract(List<Utterance> transcript, String promptVersion) {
        var markers = parse(transcript);
        var assemblies = assemble(markers);

        var issueCandidates = assemblies.values().stream()
                .map(a -> new ExtractedCandidate(new LocalKey(a.key),
                        IssueCandidate.Content.lenient(a.question, ProposedState.쟁점, a.answer, a.reason),
                        a.spans))
                .toList();

        var opinions = new ArrayList<ExtractedOpinion>();
        var tasks = new ArrayList<ExtractedTask>();
        var terms = new ArrayList<ExtractedTerm>();
        for (var m : markers) {
            switch (m.kind) {
                // 화자 라벨은 채우지 않는다 — 시뮬레이터 STT 가 화자 분리를 하지 않으므로 채우면 거짓말이다
                case OPINION -> opinions.add(new ExtractedOpinion(surviving(m.key, assemblies),
                        new Opinion.Content(null, m.body), m.span));
                case TASK -> tasks.add(new ExtractedTask(surviving(m.key, assemblies),
                        new Task.Content(m.body, assignee(m, markers)), m.span));
                case TERM -> terms.add(new ExtractedTerm(new TermCandidate.Content(m.body, m.second),
                        List.of(m.span)));
                default -> { }
            }
        }

        var meta = new Meta("marker", promptVersion, promptHash(), null);
        return new ExtractionResult(meta, issueCandidates, opinions, tasks, ExtractPort.mergeBySpelling(terms));
    }

    // ── 파싱 ────────────────────────────────────────────────

    /** 마커 하나. {@code span} 은 이 마커가 박힌 발화의 전역 번호이자 곧 근거다. */
    private record Marker(String kind, String key, String body, String second, int span) {}

    private static List<Marker> parse(List<Utterance> transcript) {
        var out = new ArrayList<Marker>();
        for (int span = 0; span < transcript.size(); span++) {
            var pieces = transcript.get(span).text().split("\\|");
            for (int i = 1; i < pieces.length; i++) {
                var piece = pieces[i].strip();
                if (markerShaped(piece)) out.add(parseOne(piece, span));
            }
        }
        return out;
    }

    /**
     * 발화에 든 {@code |} 가 전부 마커 구분자는 아니다 — <i>"그럼 A | B 중에 골라야죠"</i> 같은
     * 평범한 말이 진짜 회의록에 들어온다. 그렇다고 모르는 조각을 전부 말로 넘기면
     * <b>대본 오타가 조용해진다</b> — "대본 버그는 시끄럽게" 로 정한 그 자리가 무너진다.
     *
     * <p>가르는 선은 <b>마커 모양</b>이다: 머리(첫 {@code :} 앞)에 공백이 없고, 그 머리가
     * {@code @} 를 갖거나 아는 종류이면 마커로 읽는다. 사람이 하는 말은 {@code 단어@단어} 를
     * 거의 안 만들고 아는 종류 열도 안 만든다. 그래서 {@code 결론@x: …} 은 오타로 터지고
     * {@code B 중에 골라야죠} 는 말로 지나간다.
     *
     * <p>남는 구멍 하나 — 키를 안 쓰는 종류(의견 · 용어)의 <b>이름</b>을 틀리면 조용히 말이 된다.
     * 그 자리까지 시끄럽게 하려면 사람 말에 {@code 단어: …} 가 못 오게 해야 해서 값이 안 맞는다.
     */
    private static boolean markerShaped(String piece) {
        int colon = piece.indexOf(':');
        var head = (colon >= 0 ? piece.substring(0, colon) : piece).strip();
        if (head.isEmpty() || head.chars().anyMatch(Character::isWhitespace)) return false;
        return head.indexOf('@') >= 0 || known(head);
    }

    private static Marker parseOne(String piece, int span) {
        var head = piece;
        String body = null, second = null;

        int colon = piece.indexOf(':');
        if (colon >= 0) {
            head = piece.substring(0, colon).strip();
            body = piece.substring(colon + 1).strip();
            int equals = body.indexOf('=');
            if (equals >= 0) {
                second = blankToNull(body.substring(equals + 1));
                body = body.substring(0, equals);
            }
            body = blankToNull(body);
        }

        int at = head.indexOf('@');
        var kind = (at >= 0 ? head.substring(0, at) : head).strip();
        var key = at >= 0 ? blankToNull(head.substring(at + 1)) : null;

        if (!known(kind)) throw new IllegalArgumentException("알 수 없는 마커 종류다: " + kind);
        return new Marker(kind, key, body, second, span);
    }

    private static boolean known(String kind) {
        if (kind.equals(ISSUE) || kind.equals(ANSWER) || kind.equals(REASON)
                || kind.equals(OPINION) || kind.equals(TASK) || kind.equals(TERM)) return true;
        for (var mode : FailureMode.values()) if (mode.marker().equals(kind)) return true;
        return false;
    }

    // ── 조립 ────────────────────────────────────────────────

    /**
     * 조립 중인 후보 하나. 다 조립되면 {@link ExtractedCandidate} 가 된다.
     * 상태는 여기서 안 정한다 — 답이 정한다.
     *
     * <p>이 자리를 <i>초안</i> 이라고 부르지 않는다. {@code CONTEXT.md} 의 <b>후보</b> 항목이
     * {@code _Avoid_} 로 금지한 말이고, 영문으로 음역해도 같은 말이다 — 합의문이 포트에서
     * {@code IssueCandidateDraft} 를 지운 이유가 그것이다.
     */
    private static final class Assembly {
        final String key;
        final List<Integer> spans = new ArrayList<>();
        String question, answer, reason;

        Assembly(String key) { this.key = key; }
    }

    private Map<String, Assembly> assemble(List<Marker> markers) {
        var assemblies = new LinkedHashMap<String, Assembly>();

        // 같은 키가 반복되면 먼저 나온 본문이 이기고 근거만 더한다 — 본문 없는 이슈 마커가 그 자리다
        for (var m : markers) {
            if (!m.kind.equals(ISSUE)) continue;
            var assembly = assemblies.computeIfAbsent(m.key, Assembly::new);
            assembly.spans.add(m.span);
            if (assembly.question == null) assembly.question = m.body;
        }

        // 대본 버그는 시끄럽게. 여기서 재는 것은 "이슈 마커가 정의한 적 있는 키인가" 뿐이고,
        // 이 검사가 누락 스위치보다 먼저 도는 것이 곧 그 구분이다 — 누락으로 사라질 키는
        // 정의된 적 있는 키라 조용히 지나가고(스위치의 의도된 관측), 진짜 오타만 잡힌다
        for (var m : markers) {
            if (!refersToIssue(m.kind) || assemblies.containsKey(m.key)) continue;
            throw new IllegalArgumentException("정의된 적 없는 이슈를 가리킨다: %s@%s".formatted(m.kind, m.key));
        }

        for (var m : markers) {
            if (m.kind.equals(ANSWER) && assemblies.get(m.key).answer == null)
                assemblies.get(m.key).answer = m.body;
            if (m.kind.equals(REASON) && assemblies.get(m.key).reason == null)
                assemblies.get(m.key).reason = m.body;
        }

        // 누락이 제일 먼저다. 분할 뒤로 미루면 쪼갠 파생본이 살아남아 "누락을 켰는데 그 이슈가
        // 그대로 있는" 결과가 나온다 — 스위치 넷의 시그니처가 겹치지 않는다는 주장이 거기서 깨진다
        if (switches.contains(FailureMode.OMIT))
            for (var m : markers)
                if (m.kind.equals(FailureMode.OMIT.marker())) assemblies.remove(m.key);

        // 파생 키는 원키#sN — 쪼갠 것끼리도, 원본과도 안 겹친다
        if (switches.contains(FailureMode.OVERSPLIT)) {
            var derived = new LinkedHashMap<String, Assembly>();
            var count = new HashMap<String, Integer>();
            for (var m : markers) {
                if (!m.kind.equals(FailureMode.OVERSPLIT.marker())) continue;
                if (!assemblies.containsKey(m.key)) continue;          // 누락으로 이미 사라진 이슈
                int n = count.merge(m.key, 1, Integer::sum);
                var split = new Assembly(m.key + "#s" + n);
                split.question = m.body;
                split.spans.add(m.span);
                derived.put(split.key, split);
            }
            assemblies.putAll(derived);
        }

        if (switches.contains(FailureMode.FABRICATE))
            for (var m : markers)
                if (m.kind.equals(FailureMode.FABRICATE.marker()) && assemblies.containsKey(m.key))
                    assemblies.get(m.key).question = m.body;

        return assemblies;
    }

    /**
     * 후보가 통째로 없으면 값을 붙일 자리도 쪼갤 대상도 없는 넷.
     *
     * <p>{@code 누락} 과 {@code 무담당} 은 빠져 있다 — 누락은 없애는 것이라 헛치는 것이 무해하고,
     * 무담당은 이슈가 아니라 같은 줄의 할 일을 가리킨다. 의견·할 일의 참조는 optional 이라
     * 안 풀리면 무소속이 된다.
     */
    private static boolean refersToIssue(String kind) {
        return kind.equals(ANSWER) || kind.equals(REASON)
                || kind.equals(FailureMode.OVERSPLIT.marker()) || kind.equals(FailureMode.FABRICATE.marker());
    }

    /**
     * 참조가 안 풀리면 무소속으로 만든다 — 의견을 버리지도 이슈 후보를 지어내지도 않는다.
     * 누락 스위치로 이슈가 빠질 때 그 이슈를 참조하던 의견이 지나는 길이 이 길이다.
     */
    private static LocalKey surviving(String key, Map<String, Assembly> assemblies) {
        return key != null && assemblies.containsKey(key) ? new LocalKey(key) : null;
    }

    /** {@code 무담당@키} 는 <b>같은 줄의</b> 할 일에 적용된다 — 키만으로는 어느 할 일인지 안 정해진다. */
    private String assignee(Marker task, List<Marker> markers) {
        if (!switches.contains(FailureMode.NO_ASSIGNEE)) return task.second;
        for (var m : markers)
            if (m.kind.equals(FailureMode.NO_ASSIGNEE.marker()) && m.span == task.span
                    && Objects.equals(m.key, task.key)) return null;
        return task.second;
    }

    // ── 메타 ────────────────────────────────────────────────

    /**
     * 마커에는 프롬프트가 없다. 남는 자리를 상수로 채우면 한 회의의 세 벌이 전부 같은 해시로 떠
     * <i>"여러 벌을 나란히 놓고 판정한다"</i> 는 해시의 용도가 사라진다.
     * 마커에서 결과를 바꾸는 유일한 설정이 스위치이고, 진짜 어댑터에서 그 자리가 프롬프트 파일이다.
     */
    private String promptHash() {
        return sha256("marker:" + String.join(",", switches.stream().map(Enum::name).sorted().toList()));
    }

    private static String sha256(String s) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            var out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append("%02x".formatted(b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 은 JDK 필수 알고리즘이다", e);
        }
    }

    private static String blankToNull(String s) {
        var stripped = s.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
