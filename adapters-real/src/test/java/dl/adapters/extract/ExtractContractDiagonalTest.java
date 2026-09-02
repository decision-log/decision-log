package dl.adapters.extract;

import dl.domain.model.Model.*;
import dl.domain.ports.ExtractPort;
import dl.domain.ports.ExtractPort.*;
import dl.domain.ports.SttPort.Utterance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 그 계약이 <b>실제로 잡는지</b> 재는 대각선.
 *
 * <p>이 티켓의 구현체는 마커 하나고 마커는 여섯을 다 지킨다 — 스위트가 전부 초록불인데
 * 검사가 작동해서 초록인지 검사가 비어서 초록인지 구분이 안 된다. 남기지 않으면 #8 이
 * <i>한 번도 검증된 적 없는 스위트</i>에 기대게 되고, 그건 이 저장소가 반복해서 거부해 온 자리다.
 *
 * <pre>
 * 계약 ① 참조무결성  ② 로컬키유일  ③ 구간범위  ④ 용어표기유일  ⑤ 메타정직  ⑥ 상태·답정합
 * 위반   참조끊김 · 로컬키중복 · 구간범위밖 · 용어중복 · 토큰부분채움 · 결정인데답없음
 * </pre>
 */
class ExtractContractDiagonalTest {

    private static final List<Utterance> TRANSCRIPT = ExtractContractTest.DEFAULT_TRANSCRIPT;
    private static final String PROMPT_VERSION = ExtractContractTest.PROMPT_VERSION;

    @Test
    void 스위치_없는_마커는_여섯을_다_지킨다() {
        assertThat(failing(new MarkerExtractAdapter())).isEmpty();
    }

    @Test
    void 참조끊김은_참조무결성만_떨어뜨린다() {
        assertThat(failing(breaking(result -> {
            var opinions = new ArrayList<>(result.opinions());
            var first = opinions.getFirst();
            opinions.set(0, new ExtractedOpinion(new LocalKey("ghost"), first.content(), first.span()));
            return withOpinions(result, opinions);
        }))).containsExactly(1);
    }

    /** 키까지 그대로 복제한다 — 이름을 바꾸면 그 이름을 가리키던 참조가 함께 끊겨 ①도 떨어진다. */
    @Test
    void 로컬키중복은_로컬키유일만_떨어뜨린다() {
        assertThat(failing(breaking(result -> {
            var candidates = new ArrayList<>(result.issueCandidates());
            candidates.add(candidates.getFirst());
            return withCandidates(result, candidates);
        }))).containsExactly(2);
    }

    @Test
    void 구간범위밖은_구간범위만_떨어뜨린다() {
        assertThat(failing(breaking(result -> {
            var candidates = new ArrayList<>(result.issueCandidates());
            var first = candidates.getFirst();
            candidates.set(0, new ExtractedCandidate(first.key(), first.content(), List.of(TRANSCRIPT.size() + 5)));
            return withCandidates(result, candidates);
        }))).containsExactly(3);
    }

    @Test
    void 용어중복은_용어표기유일만_떨어뜨린다() {
        assertThat(failing(breaking(result -> {
            var terms = new ArrayList<>(result.termCandidates());
            terms.add(terms.getFirst());
            return withTerms(result, terms);
        }))).containsExactly(4);
    }

    /** 모델을 부르고도 출력이 0 토큰인 벌은 없다 — "측정되지 않았다"를 0 으로 적은 것이다. */
    @Test
    void 토큰부분채움은_메타정직만_떨어뜨린다() {
        assertThat(failing(breaking(result -> {
            var meta = result.meta();
            return withMeta(result, new Meta(meta.modelName(), meta.promptVersion(), meta.promptHash(),
                    new TokenUsage(1200, 0, 0)));
        }))).containsExactly(5);
    }

    /**
     * 답을 {@code ""} 로 비운다 — 엄격한 생성자는 null 짝맞춤만 보므로 <b>생성이 된다.</b>
     * 와이어에서 오는 {@code "answer": ""} 가 생성자를 지나치는 그 경로가 여기 재현돼 있고,
     * 그래서 계약 ⑥ 이 타입·생성자와 중복이 아니다.
     */
    @Test
    void 결정인데답없음은_상태답정합만_떨어뜨린다() {
        assertThat(failing(breaking(result -> {
            var candidates = new ArrayList<>(result.issueCandidates());
            for (int i = 0; i < candidates.size(); i++) {
                var candidate = candidates.get(i);
                if (candidate.content().state() != ProposedState.결정) continue;
                candidates.set(i, new ExtractedCandidate(candidate.key(),
                        new IssueCandidate.Content(candidate.content().question(), ProposedState.결정, "", null),
                        candidate.spans()));
                break;
            }
            return withCandidates(result, candidates);
        }))).containsExactly(6);
    }

    /** 두 겹의 나머지 반쪽 — blank 는 지나가도 {@code null} 은 생성자 관문에서 막힌다. */
    @Test
    void 답을_null_로_비우면_엄격한_생성자가_먼저_막는다() {
        assertThatThrownBy(() ->
                new IssueCandidate.Content("리버스 프록시를 무엇으로 할 것인가", ProposedState.결정, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 위반 어댑터 ─────────────────────────────────────────

    /** 마커 결과 하나를 받아 필드 한 군데씩 망가뜨린다 — 위반 어댑터가 싼 이유가 이것이다. */
    private static ExtractPort breaking(UnaryOperator<ExtractionResult> damage) {
        var marker = new MarkerExtractAdapter();
        return (transcript, promptVersion) -> damage.apply(marker.extract(transcript, promptVersion));
    }

    /** 계약 여섯을 전부 돌리고 <b>떨어진 것의 번호</b>를 준다 — 계약 테스트와 같은 함수다. */
    private static List<Integer> failing(ExtractPort port) {
        var result = port.extract(TRANSCRIPT, PROMPT_VERSION);

        record Check(int number, Runnable run) {}
        var checks = List.of(
                new Check(1, () -> ExtractContractChecks.referentialIntegrity(result)),
                new Check(2, () -> ExtractContractChecks.uniqueLocalKeys(result)),
                new Check(3, () -> ExtractContractChecks.spansInRange(result, TRANSCRIPT.size())),
                new Check(4, () -> ExtractContractChecks.uniqueTermSpellings(result)),
                new Check(5, () -> ExtractContractChecks.honestMeta(result, PROMPT_VERSION)),
                new Check(6, () -> ExtractContractChecks.stateAnswerAgreement(result)));

        var failed = new ArrayList<Integer>();
        for (var check : checks) {
            try {
                check.run().run();
            } catch (AssertionError violated) {
                failed.add(check.number());
            }
        }
        return failed;
    }

    private static ExtractionResult withCandidates(ExtractionResult r, List<ExtractedCandidate> candidates) {
        return new ExtractionResult(r.meta(), candidates, r.opinions(), r.tasks(), r.termCandidates());
    }

    private static ExtractionResult withOpinions(ExtractionResult r, List<ExtractedOpinion> opinions) {
        return new ExtractionResult(r.meta(), r.issueCandidates(), opinions, r.tasks(), r.termCandidates());
    }

    private static ExtractionResult withTerms(ExtractionResult r, List<ExtractedTerm> terms) {
        return new ExtractionResult(r.meta(), r.issueCandidates(), r.opinions(), r.tasks(), terms);
    }

    private static ExtractionResult withMeta(ExtractionResult r, Meta meta) {
        return new ExtractionResult(meta, r.issueCandidates(), r.opinions(), r.tasks(), r.termCandidates());
    }
}
