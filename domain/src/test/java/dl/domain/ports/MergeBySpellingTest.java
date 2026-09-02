package dl.domain.ports;

import dl.domain.model.Model.TermCandidate;
import dl.domain.ports.ExtractPort.ExtractedTerm;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 표기의 용어 후보를 어댑터가 한 건으로 합친다 — 병합은 {@code lenient} 와 같은 자리
 * (도메인 공용 함수)에 산다. 어댑터 셋이 각자 합치면 규칙이 셋으로 갈린다.
 */
class MergeBySpellingTest {

    private static ExtractedTerm term(String spelling, String meaning, int... spans) {
        var boxed = java.util.Arrays.stream(spans).boxed().toList();
        return new ExtractedTerm(new TermCandidate.Content(spelling, meaning), boxed);
    }

    @Test
    void 같은_표기는_한_건이_되고_근거_길이가_등장_횟수다() {
        var merged = ExtractPort.mergeBySpelling(List.of(
                term("툴 콜링", null, 7),
                term("스크럼", null, 3),
                term("툴 콜링", null, 8)));

        assertThat(merged).extracting(t -> t.content().spelling()).containsExactly("툴 콜링", "스크럼");
        assertThat(merged.getFirst().spans()).containsExactly(7, 8);
    }

    @Test
    void 뜻은_먼저_나온_것이_이긴다() {
        var merged = ExtractPort.mergeBySpelling(List.of(
                term("Caddy", "자동 HTTPS 되는 리버스 프록시", 0),
                term("Caddy", "나중에 붙은 뜻", 4)));

        assertThat(merged).hasSize(1);
        assertThat(merged.getFirst().content().meaning()).isEqualTo("자동 HTTPS 되는 리버스 프록시");
    }

    /**
     * <b>표기 문자열을 그대로 비교한다.</b> 대소문자·공백을 정규화하면 흔들림이 한 건으로 접혀
     * "같은 말인지도 모른다"는 성질이 테스트에서 사라진다.
     */
    @Test
    void 깨진_표기는_정답과_다른_것으로_남는다() {
        var merged = ExtractPort.mergeBySpelling(List.of(
                term("툴 콜링", null, 0),
                term("툴 풀링", null, 1),
                term("툴콜링", null, 2)));

        assertThat(merged).hasSize(3);
    }

    /** 근거구간이 빈 용어 후보는 포트 레코드가 애초에 만들어 주지 않는다. */
    @Test
    void 근거_없는_용어_후보는_포트에서_만들어지지_않는다() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new ExtractedTerm(new TermCandidate.Content("툴 콜링", null), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
