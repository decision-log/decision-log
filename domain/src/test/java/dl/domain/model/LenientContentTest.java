package dl.domain.model;

import dl.domain.model.Model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 엄격한 생성자 옆에 관대한 팩토리가 서 있다 (#5 합의문).
 *
 * <p>어댑터 셋(마커 · 재생 · 진짜)이 전부 {@code lenient} 를 지나고, 도메인 내부 경로는 생성자가
 * 지킨다. <b>둘의 판정이 같은 네 자리</b>가 이 테스트다 — 생성자가 거부하는 것을 팩토리가 맞춘다.
 */
class LenientContentTest {

    private static final CandidateId ID = new CandidateId("c1");
    private static final ExtractionId EXTRACTION = new ExtractionId("e1");
    private static final Evidence 근거 = new Evidence(new TranscriptId("t0"), 0);

    // ── 관대한 입구: 답이 이긴다 (합의문 표 4행) ──────────────

    @Test
    void 결정인데_답이_없으면_쟁점으로_내린다() {
        var content = IssueCandidate.Content.lenient("리버스 프록시를 무엇으로 할 것인가", ProposedState.결정, null, null);

        assertThat(content.state()).isEqualTo(ProposedState.쟁점);
        assertThat(content.answer()).isNull();
    }

    @Test
    void 답이_없으면_사유는_살아남는다() {
        var content = IssueCandidate.Content.lenient("무엇을 넣을 것인가", ProposedState.결정, null, "의견 대립");

        assertThat(content.state()).isEqualTo(ProposedState.쟁점);
        assertThat(content.undecidedReason()).isEqualTo("의견 대립");
    }

    /** 상태를 살리고 답을 버리면 그게 누락이다 — 과잉추출은 사람이 5초에 버린다. */
    @Test
    void 쟁점인데_답이_있으면_결정으로_올린다() {
        var content = IssueCandidate.Content.lenient("무엇으로 할 것인가", ProposedState.쟁점, "Caddy", null);

        assertThat(content.state()).isEqualTo(ProposedState.결정);
        assertThat(content.answer()).isEqualTo("Caddy");
    }

    /** 미결정사유는 "답이 없는 이유"라 답과 원리적으로 공존할 수 없다. */
    @Test
    void 답과_사유가_함께_오면_사유만_버린다() {
        var content = IssueCandidate.Content.lenient("무엇으로 할 것인가", ProposedState.결정, "Caddy", "의견 대립");

        assertThat(content.state()).isEqualTo(ProposedState.결정);
        assertThat(content.answer()).isEqualTo("Caddy");
        assertThat(content.undecidedReason()).isNull();
    }

    @Test
    void 빈_문자열은_없는_것으로_정규화된다() {
        var content = IssueCandidate.Content.lenient("무엇으로 할 것인가", ProposedState.결정, "  ", "\t");

        assertThat(content.state()).isEqualTo(ProposedState.쟁점);
        assertThat(content.answer()).isNull();
        assertThat(content.undecidedReason()).isNull();
    }

    // ── 엄격한 생성자: 팩토리가 맞춘 네 자리를 그대로 거부한다 ──

    @Test
    void 결정인데_답이_없으면_생성자가_거부한다() {
        assertThatThrownBy(() -> new IssueCandidate.Content("무엇으로 할 것인가", ProposedState.결정, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 결정인데_답이_없고_사유만_있으면_생성자가_거부한다() {
        assertThatThrownBy(() -> new IssueCandidate.Content("무엇으로 할 것인가", ProposedState.결정, null, "의견 대립"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 쟁점인데_답이_있으면_생성자가_거부한다() {
        assertThatThrownBy(() -> new IssueCandidate.Content("무엇으로 할 것인가", ProposedState.쟁점, "Caddy", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 결정에_사유를_붙이면_생성자가_거부한다() {
        assertThatThrownBy(() -> new IssueCandidate.Content("무엇으로 할 것인가", ProposedState.결정, "Caddy", "의견 대립"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 성립하는_두_모양은_생성자를_그냥_지난다() {
        assertThat(new IssueCandidate.Content("무엇으로 할 것인가", ProposedState.쟁점, null, "정보 부족").undecidedReason())
                .isEqualTo("정보 부족");
        assertThat(new IssueCandidate.Content("무엇으로 할 것인가", ProposedState.결정, "Caddy", null).answer())
                .isEqualTo("Caddy");
    }

    // ── 근거가 비면 터진다 ──────────────────────────────────

    /**
     * 이것만 관대한 팩토리가 못 구제한다 — 없는 것을 지어낼 수 없고, 근거 없는 후보는
     * 확인 화면에서 사람이 판단할 재료가 0 인 항목이다.
     */
    @Test
    void 근거_없는_이슈_후보는_만들어지지_않는다() {
        var content = IssueCandidate.Content.lenient("무엇으로 할 것인가", ProposedState.쟁점, null, null);

        assertThatThrownBy(() -> new IssueCandidate(ID, EXTRACTION, content, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new IssueCandidate(ID, EXTRACTION, content, List.of(근거)).evidence()).containsExactly(근거);
    }

    @Test
    void 근거_없는_용어_후보는_만들어지지_않는다() {
        var content = new TermCandidate.Content("툴 콜링", null);

        assertThatThrownBy(() -> new TermCandidate(new TermCandidateId("x1"), EXTRACTION, content, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 의견·할 일의 근거는 단수다 — 비지 않음이 not-null 하나로 강제된다. */
    @Test
    void 근거_없는_의견과_할일도_만들어지지_않는다() {
        assertThatThrownBy(() -> new Opinion(new OpinionId("o1"), EXTRACTION, null,
                new Opinion.Content(null, "nginx가 익숙하다"), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new Task(new TaskId("k1"), EXTRACTION, null,
                new Task.Content("툴 콜링 명세 조사", null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
