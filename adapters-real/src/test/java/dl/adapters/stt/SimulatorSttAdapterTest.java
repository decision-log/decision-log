package dl.adapters.stt;

import dl.adapters.stt.SimulatorSttAdapter.Mode;
import dl.adapters.stt.SimulatorSttAdapter.Rule;
import dl.domain.ports.SttPort.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 시뮬레이터가 <b>자기 이름으로</b> 주장하는 것들. 계약이 아니다 —
 * 진짜 어댑터는 이 중 어느 것도 지킬 수 없다(대본이 없고 결정적이지도 않다).
 *
 * <p>여기서 검증하는 것은 ADR 0005 의 주장 하나다: <i>컨텍스트가 바뀌면 전사가 달라진다.</i>
 * 그 주장이 이득과 대가 양쪽으로 모델링돼 있어야 사슬이 실제보다 좋아 보이지 않는다.
 */
class SimulatorSttAdapterTest {

    /** ADR 0005 의 예시 규칙 — 실측이 아니라 역방향 기제를 보이려고 만든 것이다. */
    private static final Rule CADDY = new Rule("Caddy", Mode.STEADY, List.of("캐디"), List.of("캐시"));

    // ── 결정성 ──────────────────────────────────────────────

    @Test
    void 시드를_고정하면_같은_대본_같은_컨텍스트에_같은_회의록이_나온다() {
        var script = "툴 콜링이 툴 콜링을 부르고 툴 콜링이 또 툴 콜링을 부른다";

        assertThat(transcription(simulator(7L), script)).isEqualTo(transcription(simulator(7L), script));

        // 같은 어댑터를 두 번 불러도 같다 — 호출마다 시드를 다시 건다
        var one = simulator(7L);
        assertThat(transcription(one, script)).isEqualTo(transcription(one, script));

        // 시드가 다르면 흔들림이 다르게 뽑힌다
        assertThat(transcription(simulator(1L), script)).isNotEqualTo(transcription(simulator(2L), script));
    }

    // ── 컨텍스트가 전사를 바꾼다 ────────────────────────────

    @Test
    void 컨텍스트에_용어를_넣으면_고착이_사라진다() {
        var script = "스크럼은 매일 아침에 합니다";

        assertThat(transcription(simulator(7L), script))
                .containsExactly("시끄러움은 매일 아침에 합니다");

        assertThat(transcription(simulator(7L), script, term("스크럼")))
                .containsExactly("스크럼은 매일 아침에 합니다");
    }

    /**
     * 주입의 대가. 컨텍스트에 {@code Caddy} 를 넣으면 "응답 캐시"가 그쪽으로 끌려간다.
     * 이 방향이 없으면 공급자 평가 절차 3번이 재는 값이 0 으로 모델링된다.
     */
    @Test
    void 컨텍스트에_있는_용어_쪽으로_엉뚱한_말이_끌려간다() {
        var script = "응답 캐시를 걸어두면 빨라집니다";
        var stt = new SimulatorSttAdapter(List.of(CADDY), 7L);

        assertThat(transcription(stt, script))
                .as("컨텍스트에 없으면 아무 일도 안 일어난다")
                .containsExactly("응답 캐시를 걸어두면 빨라집니다");

        assertThat(transcription(stt, script, term("Caddy")))
                .as("넣은 어휘 쪽으로 끌려간다")
                .containsExactly("응답 Caddy를 걸어두면 빨라집니다");
    }

    // ── 두 모드 ─────────────────────────────────────────────

    @Test
    void 일관은_한_회차_안에서_같은_형태로_깨진다() {
        var transcript = transcription(simulator(7L), "스크럼 이야기\n또 스크럼 이야기\n계속 스크럼 이야기");

        assertThat(transcript).allSatisfy(line -> assertThat(line).contains("시끄러움"));
        assertThat(transcript).noneSatisfy(line -> assertThat(line).contains("스크럼"));
    }

    /** 흔들림은 등장마다 다시 뽑는다 — 한 줄 안에서도 형태가 갈려야 한다. */
    @Test
    void 흔들림은_같은_말을_여러_형태로_깨뜨린다() {
        var oneLine = "툴 콜링 ".repeat(12).strip();
        var produced = transcription(simulator(7L), oneLine).getFirst();

        var forms = MeasuredCorruptionRules.TOOL_CALLING.corruptions().stream().filter(produced::contains).toList();
        assertThat(forms).as("나온 회의록: %s", produced).hasSizeGreaterThan(1);
        assertThat(produced).doesNotContain("툴 콜링");
    }

    // ── 규칙끼리 부딪히는 자리 ──────────────────────────────

    @Test
    void gitignore가_git_규칙에_먼저_먹히지_않는다() {
        var produced = transcription(simulator(7L), ".gitignore 에 .git 을 적는다").getFirst();

        assertThat(produced).isEqualTo("GD 근원 에 점기 을 적는다");
        assertThat(produced).as("긴 정답부터 걸려야 한다").doesNotContain("점기ignore");
    }

    // ── 대본이 아닌 것 ──────────────────────────────────────

    @Test
    void 텍스트가_아니면_조용히_쓰레기를_만들지_않고_실패한다() {
        var audioLikeBytes = new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0x80, 0x41};

        assertThatThrownBy(() -> simulator(7L)
                .requestTranscription(new Audio(audioLikeBytes, "회의녹음.mp3"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대본")
                .hasMessageContaining("회의녹음.mp3");
    }

    // ── 헬퍼 ────────────────────────────────────────────────

    private static SimulatorSttAdapter simulator(long seed) {
        return new SimulatorSttAdapter(MeasuredCorruptionRules.all(), seed);
    }

    private static List<String> transcription(SimulatorSttAdapter stt, String script, ContextItem... context) {
        var id = stt.requestTranscription(new Audio(script.getBytes(StandardCharsets.UTF_8), "m.txt"), List.of(context));
        return stt.result(id).transcript().stream().map(Utterance::text).toList();
    }

    private static ContextItem term(String spelling) {
        return new ContextItem(ContextKind.TERM, spelling, null);
    }
}
