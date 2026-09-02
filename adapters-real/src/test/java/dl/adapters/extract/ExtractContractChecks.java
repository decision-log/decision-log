package dl.adapters.extract;

import dl.domain.model.Model.ProposedState;
import dl.domain.ports.ExtractPort.ExtractionResult;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계약 여섯 — 참조무결성 · 로컬키유일 · 구간범위 · 용어표기유일 · 메타정직 · 상태·답정합.
 *
 * <p>어댑터가 아니라 {@code ExtractPort} 에 걸리므로 #8 의 재생·진짜 어댑터가 그냥 끼워진다.
 * <b>결정성 · 대본전량 · 스위치시그니처는 여기 없다</b> — 비결정적 LLM 이 버그 없이도 떨어지고,
 * 대본전량은 <i>관대하게 뽑는</i> 올바른 동작을 벌준다.
 *
 * <p>검사를 함수로 빼 둔 것은 <b>계약 테스트와 대각선이 같은 함수를 쓰기 위해서다.</b>
 * 구현체가 마커 하나뿐이면 스위트가 전부 초록불일 때 검사가 작동해서 초록인지 비어서 초록인지
 * 구분이 안 된다. 위반 어댑터 여섯이 자기 불변식에서만 떨어지는 것을 같은 함수로 재면 그게 구분된다.
 */
final class ExtractContractChecks {

    /** ① 참조무결성 — 안 풀리는 참조를 남기지 않는다. 무소속({@code null})은 위반이 아니다. */
    static void referentialIntegrity(ExtractionResult result) {
        var keys = result.issueCandidates().stream().map(c -> c.key().value()).toList();

        var refs = new ArrayList<String>();
        for (var o : result.opinions()) if (o.issueRef() != null) refs.add(o.issueRef().value());
        for (var t : result.tasks()) if (t.issueRef() != null) refs.add(t.issueRef().value());

        assertThat(keys).as("의견·할 일이 가리키는 로컬키가 결과 안에 있다").containsAll(refs);
    }

    /** ② 로컬키유일 — 한 결과 안에서 이름이 겹치면 참조가 어느 쪽인지 안 정해진다. */
    static void uniqueLocalKeys(ExtractionResult result) {
        assertThat(result.issueCandidates()).extracting(c -> c.key().value()).doesNotHaveDuplicates();
    }

    /**
     * ③ 구간범위 — 근거가 회의록 밖을 가리키면 <i>"깨진 표기 옆에 그 말이 나온 회의록 한 줄"</i> 이
     * 원리적으로 불가능하다. 저장 층에서는 {@code utterance(transcript_id, seq)} FK 가 함께 지킨다.
     */
    static void spansInRange(ExtractionResult result, int transcriptSize) {
        var spans = new ArrayList<Integer>();
        for (var c : result.issueCandidates()) spans.addAll(c.spans());
        for (var t : result.termCandidates()) spans.addAll(t.spans());
        for (var o : result.opinions()) spans.add(o.span());
        for (var t : result.tasks()) spans.add(t.span());

        assertThat(spans).allSatisfy(span ->
                assertThat(span).as("근거구간").isBetween(0, transcriptSize - 1));
    }

    /**
     * ④ 용어표기유일 — 같은 표기가 한 건으로 합쳐졌다.
     * 표기를 <b>그대로</b> 비교한다: 정규화하면 {@code 툴 풀링} 과 {@code 툴 콜링} 이 접혀 흔들림이 안 보인다.
     */
    static void uniqueTermSpellings(ExtractionResult result) {
        assertThat(result.termCandidates()).extracting(t -> t.content().spelling()).doesNotHaveDuplicates();
    }

    /**
     * ⑤ 메타정직 — 토큰수는 통째로 비우거나 전부 진짜여야 한다.
     * {@code 0} 은 비교표에서 "제일 싸다"로 읽히므로 <i>측정되지 않았다</i> 를 그렇게 적으면 거짓이다.
     */
    static void honestMeta(ExtractionResult result, String requestedPromptVersion) {
        var meta = result.meta();
        assertThat(meta.modelName()).as("모델명은 이 결과를 만든 것의 진짜 이름이다").isNotBlank();
        assertThat(meta.promptHash()).as("프롬프트해시").isNotBlank();
        assertThat(meta.promptVersion()).as("요청한 프롬프트버전을 그대로 돌려준다").isEqualTo(requestedPromptVersion);

        var tokens = meta.tokens();
        if (tokens == null) return;
        assertThat(tokens.input()).as("입력 토큰").isPositive();
        assertThat(tokens.output()).as("출력 토큰").isPositive();
        assertThat(tokens.cacheHit()).as("캐시적중은 입력의 일부다").isBetween(0L, tokens.input());
    }

    /**
     * ⑥ 상태·답정합 — 타입·생성자와 중복돼 보이지만 아니다.
     * #8 의 어댑터는 LLM 이 뱉은 JSON 을 <b>역직렬화</b>하므로 생성자를 우회하는 경로가 생긴다.
     * 그래서 생성자가 보는 null 짝맞춤보다 한 겹 넓게 — <b>blank 까지</b> 답이 없는 것으로 본다.
     */
    static void stateAnswerAgreement(ExtractionResult result) {
        for (var candidate : result.issueCandidates()) {
            var content = candidate.content();
            var hasAnswer = content.answer() != null && !content.answer().isBlank();

            assertThat(content.state() == ProposedState.결정)
                    .as("결정 ⟺ 답 있음 (%s: 상태 %s · 답 '%s')",
                            candidate.key().value(), content.state(), content.answer())
                    .isEqualTo(hasAnswer);
            if (content.state() == ProposedState.결정)
                assertThat(content.undecidedReason())
                        .as("미결정사유는 쟁점에만 (%s)", candidate.key().value())
                        .isNull();
        }
    }

    private ExtractContractChecks() {}
}
