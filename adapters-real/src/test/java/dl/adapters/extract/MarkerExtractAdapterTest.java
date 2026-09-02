package dl.adapters.extract;

import dl.adapters.extract.MarkerExtractAdapter.FailureMode;
import dl.adapters.stt.MeasuredCorruptionRules;
import dl.adapters.stt.SimulatorSttAdapter;
import dl.domain.model.Model.ProposedState;
import dl.domain.ports.ExtractPort.ExtractionResult;
import dl.domain.ports.SttPort.Audio;
import dl.domain.ports.SttPort.Utterance;
import dl.script.Scripts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 마커 추출기가 <b>자기 이름으로</b> 주장하는 것들. 계약이 아니다 —
 * 결정성은 정상 LLM 을 버그 없이도 떨어뜨리고, 대본전량은 <i>관대하게 뽑는</i> 올바른 동작을
 * {@code ==} 로 벌준다 (부모 이슈 AC).
 *
 * <p>기본 대본의 재고를 여기서 동결한다. 대본 문구를 바꾸면 이 숫자도 같이 바꾼다.
 */
class MarkerExtractAdapterTest {

    private static final List<Utterance> SCRIPT = ExtractContractTest.DEFAULT_TRANSCRIPT;

    // ── 결정성 ──────────────────────────────────────────────

    @Test
    void 같은_대본을_두_번_읽으면_같은_결과다() {
        var adapter = new MarkerExtractAdapter();

        assertThat(adapter.extract(SCRIPT, "1")).isEqualTo(adapter.extract(SCRIPT, "1"));
        assertThat(new MarkerExtractAdapter().extract(SCRIPT, "1"))
                .isEqualTo(new MarkerExtractAdapter().extract(SCRIPT, "1"));
    }

    // ── 대본전량 ────────────────────────────────────────────

    @Test
    void 대본에_달아둔_정답을_전량_읽는다() {
        var result = new MarkerExtractAdapter().extract(SCRIPT, "1");

        assertThat(result.issueCandidates()).extracting(c -> c.content().question())
                .containsExactly("리버스 프록시를 무엇으로 할 것인가", "스크럼을 매일 할 것인가",
                        ".gitignore 에 무엇을 넣을 것인가", "툴 콜링 실패를 어떻게 처리할 것인가");

        assertThat(result.opinions()).hasSize(4);
        assertThat(result.opinions()).filteredOn(o -> o.issueRef() == null)
                .as("어느 이슈에도 안 붙는 의견이 실제로 나온다").hasSize(1);

        assertThat(result.tasks()).extracting(t -> t.content().text())
                .containsExactly("리버스 프록시 설정", "툴 콜링 명세 조사");
        assertThat(result.tasks()).extracting(t -> t.content().assignee())
                .containsExactly("가영", null);

        assertThat(result.termCandidates()).extracting(t -> t.content().spelling())
                .containsExactly("Caddy", "스크럼", ".gitignore", "툴 콜링");
        assertThat(result.termCandidates().getLast().spans())
                .as("근거구간의 길이가 곧 등장 횟수다").hasSize(2);
        assertThat(result.termCandidates().getFirst().content().meaning())
                .isEqualTo("자동 HTTPS 되는 리버스 프록시");
    }

    // ── 스위치 시그니처 (넷이 겹치지 않는다) ─────────────────

    @Test
    void 과잉분할은_이슈를_쪼개고_파생_키가_유일하다() {
        var result = new MarkerExtractAdapter(Set.of(FailureMode.OVERSPLIT)).extract(SCRIPT, "1");

        assertThat(result.issueCandidates()).hasSize(8);
        assertThat(result.issueCandidates()).extracting(c -> c.key().value()).doesNotHaveDuplicates();
        assertThat(result.issueCandidates()).extracting(c -> c.content().question())
                .contains("어느 리버스 프록시를 쓸 것인가", "스크럼 시간을 언제로 할 것인가");

        assertThat(result.opinions()).as("나머지는 불변").hasSize(4);
        assertThat(result.tasks()).hasSize(2);
        assertThat(result.termCandidates()).hasSize(4);
    }

    @Test
    void 작문오염은_개수는_그대로_두고_질문을_바꾼다() {
        var result = new MarkerExtractAdapter(Set.of(FailureMode.FABRICATE)).extract(SCRIPT, "1");

        assertThat(result.issueCandidates()).extracting(c -> c.content().question())
                .containsExactly("배포를 어떻게 할 것인가", "회의 시간을 줄일 것인가",
                        ".gitignore 에 무엇을 넣을 것인가", "로깅을 어떻게 할 것인가");
        assertThat(result.opinions()).hasSize(4);
    }

    /** 이슈가 빠져도 그 이슈를 참조하던 의견은 무소속으로 살아남는다 — 개수가 안 준다. */
    @Test
    void 누락은_이슈를_지우고_그_의견을_무소속으로_만든다() {
        var result = new MarkerExtractAdapter(Set.of(FailureMode.OMIT)).extract(SCRIPT, "1");

        assertThat(result.issueCandidates()).extracting(c -> c.key().value())
                .containsExactly("proxy", "scrum", "tools");
        assertThat(result.opinions()).hasSize(4);
        assertThat(result.opinions()).filteredOn(o -> o.issueRef() == null).hasSize(2);
        assertThat(result.termCandidates()).as("용어는 불변").hasSize(4);
    }

    /**
     * 누락이 분할보다 먼저 돌아야 한다. 뒤로 미루면 쪼갠 파생본이 살아남아 <b>누락을 켰는데
     * 그 이슈가 그대로 있는</b> 결과가 나오고, 스위치 넷의 시그니처가 겹치지 않는다는
     * 이 추출기의 근거가 조합 하나에서 무너진다.
     */
    @Test
    void 누락된_이슈는_쪼개지지도_않는다() {
        var result = new MarkerExtractAdapter(EnumSet.of(FailureMode.OMIT, FailureMode.OVERSPLIT))
                .extract(SCRIPT, "1");

        assertThat(result.issueCandidates()).extracting(c -> c.key().value())
                .containsExactly("proxy", "scrum", "tools", "proxy#s1", "scrum#s1", "tools#s1");
        assertThat(result.issueCandidates()).extracting(c -> c.content().question())
                .as("누락된 ignore 의 분할 파생본이 살아남지 않는다")
                .doesNotContain("빌드 산출물을 어디까지 무시할 것인가");
    }

    @Test
    void 담당자없음은_같은_줄의_할_일에서_담당자를_뗀다() {
        var result = new MarkerExtractAdapter(Set.of(FailureMode.NO_ASSIGNEE)).extract(SCRIPT, "1");

        assertThat(result.tasks()).hasSize(2);
        assertThat(result.tasks()).extracting(t -> t.content().assignee()).containsOnlyNulls();
    }

    // ── 마커 의미론 ─────────────────────────────────────────

    @Test
    void 본문_없는_이슈_마커는_근거만_더한다() {
        var result = new MarkerExtractAdapter().extract(SCRIPT, "1");

        assertThat(byKey(result, "scrum").spans())
                .as("같은 이슈가 여러 줄에 걸치는 자리다").containsExactly(3, 4);
        assertThat(byKey(result, "proxy").spans()).containsExactly(0);
    }

    /** 상태는 마커가 안 만든다 — 답이 있으면 관대한 팩토리가 결정으로 올린다. */
    @Test
    void 답이_상태를_올리고_사유는_쟁점에_남는다() {
        var result = new MarkerExtractAdapter().extract(SCRIPT, "1");

        assertThat(byKey(result, "proxy").content().state()).isEqualTo(ProposedState.결정);
        assertThat(byKey(result, "proxy").content().answer()).isEqualTo("Caddy");
        assertThat(byKey(result, "scrum").content().answer()).isEqualTo("매일 아침 15분");

        assertThat(byKey(result, "ignore").content().state()).isEqualTo(ProposedState.쟁점);
        assertThat(byKey(result, "ignore").content().undecidedReason()).isEqualTo("의견 대립");
        assertThat(byKey(result, "tools").content().undecidedReason()).isEqualTo("정보 부족");
    }

    /** 시뮬레이터 STT 가 화자 분리를 하지 않는다 — 거짓 라벨을 채우지 않는다. */
    @Test
    void 화자_라벨은_전부_비어_있다() {
        var result = new MarkerExtractAdapter().extract(SCRIPT, "1");

        assertThat(result.opinions()).extracting(o -> o.content().speakerLabel()).containsOnlyNulls();
    }

    @Test
    void 메타는_이_결과를_만든_것의_이름을_준다() {
        var meta = new MarkerExtractAdapter().extract(SCRIPT, "7").meta();

        assertThat(meta.modelName()).isEqualTo("marker");
        assertThat(meta.promptVersion()).isEqualTo("7");
        assertThat(meta.tokens()).as("모델을 안 부르므로 0 이 아니라 비운다").isNull();
    }

    /**
     * 해시의 용도가 "한 회의에 여러 벌을 나란히 놓고 판정한다" 라 상수가 들어가면
     * 세 벌이 전부 같은 해시로 뜬다. 마커에서 결과를 바꾸는 유일한 설정이 스위치다.
     */
    @Test
    void 프롬프트해시가_스위치_조합의_지문이다() {
        var none = hash(Set.of());
        var one = hash(Set.of(FailureMode.OMIT));
        var two = hash(EnumSet.of(FailureMode.OMIT, FailureMode.OVERSPLIT));
        var twoAgain = hash(EnumSet.of(FailureMode.OVERSPLIT, FailureMode.OMIT));

        assertThat(none).isNotEqualTo(one);
        assertThat(one).isNotEqualTo(two);
        assertThat(two).as("순서가 달라도 같은 조합이면 같다").isEqualTo(twoAgain);
        assertThat(none).as("상수가 아니라 해시다").hasSize(64).matches("[0-9a-f]+");
    }

    /** 대본 버그는 시끄럽게 — 잡이 실패하고 사유가 화면에 뜬다. */
    @Test
    void 어디에도_정의된_적_없는_키를_가리키면_터진다() {
        var script = utterances(List.of("이거 하나만 봅시다 | 답@ghost: 아무거나"));

        assertThatThrownBy(() -> new MarkerExtractAdapter().extract(script, "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    /**
     * {@code |} 가 든 평범한 발화가 진짜 회의록에 들어온다 — 그걸 마커로 읽어 잡을 죽이면
     * 안 되고, 그렇다고 모르는 조각을 다 넘기면 대본 오타가 조용해진다.
     */
    @Test
    void 마커가_아닌_조각은_사람이_한_말이다() {
        var script = utterances(List.of("그럼 A | B 중에 골라야죠 | 이슈@pick: 무엇을 고를 것인가"));

        var result = new MarkerExtractAdapter().extract(script, "1");

        assertThat(result.issueCandidates()).extracting(c -> c.content().question())
                .containsExactly("무엇을 고를 것인가");
        assertThat(result.opinions()).isEmpty();
    }

    @Test
    void 알_수_없는_마커_종류도_터진다() {
        var script = utterances(List.of("이거 하나만 봅시다 | 결론@x: 아무거나"));

        assertThatThrownBy(() -> new MarkerExtractAdapter().extract(script, "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("결론");
    }

    // ── 오염 사슬 ───────────────────────────────────────────

    /**
     * 가장 값진 테스트를 떠받치는 기제다 — 오염 시뮬레이터의 {@code line.replace()} 가
     * <b>마커 내부까지</b> 친다. 그래서 대본 아래에 따로 정답 블록을 두면 안 되고,
     * 그래서 추출기 쪽에 전파 스위치를 또 달면 사슬의 회복이 가려진다.
     */
    @Test
    void STT_오염이_마커_내부를_쳐서_이슈_제목에_박힌다() {
        var stt = new SimulatorSttAdapter(MeasuredCorruptionRules.all(), 7L);
        var job = stt.requestTranscription(
                new Audio(Scripts.defaultScript().getBytes(StandardCharsets.UTF_8), "m.txt"), List.of());

        var result = new MarkerExtractAdapter().extract(stt.result(job).transcript(), "1");

        assertThat(byKey(result, "scrum").content().question())
                .as("용어집이 비어 있어 정답이 고착으로 깨졌다").contains("시끄러움");
    }

    // ── 헬퍼 ────────────────────────────────────────────────

    private static String hash(Set<FailureMode> switches) {
        return new MarkerExtractAdapter(switches).extract(SCRIPT, "1").meta().promptHash();
    }

    private static dl.domain.ports.ExtractPort.ExtractedCandidate byKey(ExtractionResult result, String key) {
        return result.issueCandidates().stream()
                .filter(c -> c.key().value().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("그런 후보가 없다: " + key));
    }

    private static List<Utterance> utterances(List<String> lines) {
        return ExtractContractTest.asUtterances(lines);
    }
}
