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
    private static final Rule 캐디 = new Rule("Caddy", Mode.일관, List.of("캐디"), List.of("캐시"));

    // ── 결정성 ──────────────────────────────────────────────

    @Test
    void 시드를_고정하면_같은_대본_같은_컨텍스트에_같은_회의록이_나온다() {
        var 대본 = "툴 콜링이 툴 콜링을 부르고 툴 콜링이 또 툴 콜링을 부른다";

        assertThat(전사(시뮬레이터(7L), 대본)).isEqualTo(전사(시뮬레이터(7L), 대본));

        // 같은 어댑터를 두 번 불러도 같다 — 호출마다 시드를 다시 건다
        var 한벌 = 시뮬레이터(7L);
        assertThat(전사(한벌, 대본)).isEqualTo(전사(한벌, 대본));

        // 시드가 다르면 흔들림이 다르게 뽑힌다
        assertThat(전사(시뮬레이터(1L), 대본)).isNotEqualTo(전사(시뮬레이터(2L), 대본));
    }

    // ── 컨텍스트가 전사를 바꾼다 ────────────────────────────

    @Test
    void 컨텍스트에_용어를_넣으면_고착이_사라진다() {
        var 대본 = "스크럼은 매일 아침에 합니다";

        assertThat(전사(시뮬레이터(7L), 대본))
                .containsExactly("시끄러움은 매일 아침에 합니다");

        assertThat(전사(시뮬레이터(7L), 대본, 용어("스크럼")))
                .containsExactly("스크럼은 매일 아침에 합니다");
    }

    /**
     * 주입의 대가. 컨텍스트에 {@code Caddy} 를 넣으면 "응답 캐시"가 그쪽으로 끌려간다.
     * 이 방향이 없으면 공급자 평가 절차 3번이 재는 값이 0 으로 모델링된다.
     */
    @Test
    void 컨텍스트에_있는_용어_쪽으로_엉뚱한_말이_끌려간다() {
        var 대본 = "응답 캐시를 걸어두면 빨라집니다";
        var stt = new SimulatorSttAdapter(List.of(캐디), 7L);

        assertThat(전사(stt, 대본))
                .as("컨텍스트에 없으면 아무 일도 안 일어난다")
                .containsExactly("응답 캐시를 걸어두면 빨라집니다");

        assertThat(전사(stt, 대본, 용어("Caddy")))
                .as("넣은 어휘 쪽으로 끌려간다")
                .containsExactly("응답 Caddy를 걸어두면 빨라집니다");
    }

    // ── 두 모드 ─────────────────────────────────────────────

    @Test
    void 일관은_한_회차_안에서_같은_형태로_깨진다() {
        var 회의록 = 전사(시뮬레이터(7L), "스크럼 이야기\n또 스크럼 이야기\n계속 스크럼 이야기");

        assertThat(회의록).allSatisfy(줄 -> assertThat(줄).contains("시끄러움"));
        assertThat(회의록).noneSatisfy(줄 -> assertThat(줄).contains("스크럼"));
    }

    /** 흔들림은 등장마다 다시 뽑는다 — 한 줄 안에서도 형태가 갈려야 한다. */
    @Test
    void 흔들림은_같은_말을_여러_형태로_깨뜨린다() {
        var 한줄 = "툴 콜링 ".repeat(12).strip();
        var 나온것 = 전사(시뮬레이터(7L), 한줄).getFirst();

        var 형태들 = 실측오염규칙.툴콜링.오염형().stream().filter(나온것::contains).toList();
        assertThat(형태들).as("나온 회의록: %s", 나온것).hasSizeGreaterThan(1);
        assertThat(나온것).doesNotContain("툴 콜링");
    }

    // ── 규칙끼리 부딪히는 자리 ──────────────────────────────

    @Test
    void gitignore가_git_규칙에_먼저_먹히지_않는다() {
        var 나온것 = 전사(시뮬레이터(7L), ".gitignore 에 .git 을 적는다").getFirst();

        assertThat(나온것).isEqualTo("GD 근원 에 점기 을 적는다");
        assertThat(나온것).as("긴 정답부터 걸려야 한다").doesNotContain("점기ignore");
    }

    // ── 대본이 아닌 것 ──────────────────────────────────────

    @Test
    void 텍스트가_아니면_조용히_쓰레기를_만들지_않고_실패한다() {
        var 진짜오디오처럼 = new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0x80, 0x41};

        assertThatThrownBy(() -> 시뮬레이터(7L)
                .전사요청(new Audio(진짜오디오처럼, "회의녹음.mp3"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대본")
                .hasMessageContaining("회의녹음.mp3");
    }

    // ── 헬퍼 ────────────────────────────────────────────────

    private static SimulatorSttAdapter 시뮬레이터(long 시드) {
        return new SimulatorSttAdapter(실측오염규칙.전량(), 시드);
    }

    private static List<String> 전사(SimulatorSttAdapter stt, String 대본, ContextItem... 컨텍스트) {
        var id = stt.전사요청(new Audio(대본.getBytes(StandardCharsets.UTF_8), "m.txt"), List.of(컨텍스트));
        return stt.결과(id).회의록().stream().map(Utterance::text).toList();
    }

    private static ContextItem 용어(String 표기) {
        return new ContextItem(ContextKind.용어, 표기, null);
    }
}
