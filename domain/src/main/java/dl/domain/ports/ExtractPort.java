package dl.domain.ports;

import dl.domain.model.Model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * seams.md ⓶ — 출력 스키마는 도메인이 소유한다. 프롬프트·모델 교체가 스키마를 건드리면
 * 프롬프트를 고칠 때마다 마이그레이션이 붙는다.
 *
 * <p><b>어댑터가 만든 참조는 포트를 넘을 때까지만 산다.</b> 로컬키도 구간 번호도 한 추출결과
 * 안에서만 유효한 이름이라, 저장 경계에서 후보 ID 와 {@code (회의록, 순번)} 으로 한 번 해소된다.
 * 그래서 포트 레코드와 저장 레코드가 {@code …Content} 만 공유하고 바깥이 갈린다 —
 * 층마다 타입이 다른 필드가 그 둘뿐이다.
 *
 * <p>{@code Extracted} 접두어는 회차오케스트레이터가 두 층의 레코드를 한 파일에서 쓰기 때문이다.
 * 이름이 층을 말해 주는 쪽을 고른다.
 */
public interface ExtractPort {
    ExtractionResult extract(List<SttPort.Utterance> transcript, String promptVersion);

    /** 추출결과 안에서만 유효한 이름. 리스트 인덱스도 제목 문자열도 아니다. */
    record LocalKey(String value) {}

    record ExtractionResult(Meta meta, List<ExtractedCandidate> issueCandidates,
                            List<ExtractedOpinion> opinions, List<ExtractedTask> tasks,
                            List<ExtractedTerm> termCandidates) {
        public ExtractionResult {
            issueCandidates = List.copyOf(issueCandidates);
            opinions = List.copyOf(opinions);
            tasks = List.copyOf(tasks);
            termCandidates = List.copyOf(termCandidates);
        }
    }

    /**
     * 근거가 비면 잡이 실패로 끝나고 사유가 화면에 뜬다 — 없는 것을 지어낼 수 없고
     * "전체 회의록"을 근거로 붙이는 것은 거짓말이다.
     *
     * <p><b>레코드는 자기 필드의 지역 불변식만 지킨다.</b> 로컬키 유일이나 참조 무결 같은
     * 레코드 사이의 것은 계약 검사가 재는 몫이다 — 생성자에 두면 위반을 표현할 수 없어
     * 계약이 작동하는지도 잴 수 없게 된다.
     */
    record ExtractedCandidate(LocalKey key, IssueCandidate.Content content, List<Integer> spans) {
        public ExtractedCandidate {
            if (spans.isEmpty()) throw new IllegalArgumentException("근거구간이 빈 이슈 후보다: " + key);
            spans = List.copyOf(spans);
        }
    }

    /** {@code issueRef} 는 optional 이다 — 안 붙는 의견을 버리지도, 붙일 후보를 지어내지도 않는다. */
    record ExtractedOpinion(LocalKey issueRef, Opinion.Content content, int span) {}

    record ExtractedTask(LocalKey issueRef, Task.Content content, int span) {}

    record ExtractedTerm(TermCandidate.Content content, List<Integer> spans) {
        public ExtractedTerm {
            if (spans.isEmpty())
                throw new IllegalArgumentException("근거구간이 빈 용어 후보다: " + content.spelling());
            spans = List.copyOf(spans);
        }
    }

    /**
     * 같은 표기의 용어 후보를 한 건으로 합친다 — 근거구간의 길이가 곧 등장 횟수가 된다.
     *
     * <p><b>표기 문자열을 그대로 비교한다.</b> 대소문자·공백을 정규화하면 {@code 툴 풀링} 과
     * {@code 툴 콜링} 이 한 건으로 접혀 흔들림이 안 보인다. 뜻은 먼저 나온 것이 이긴다.
     *
     * <p>어댑터 셋(마커 · 재생 · 진짜)이 부르는 도메인 공용 함수다 — 관대한 팩토리와 같은 자리다.
     * 어댑터마다 자기 손으로 합치면 규칙이 셋으로 갈린다.
     */
    static List<ExtractedTerm> mergeBySpelling(List<ExtractedTerm> terms) {
        var merged = new LinkedHashMap<String, ExtractedTerm>();
        for (var term : terms) {
            merged.merge(term.content().spelling(), term, (first, next) -> {
                var spans = new ArrayList<>(first.spans());
                spans.addAll(next.spans());
                return new ExtractedTerm(first.content(), spans);
            });
        }
        return List.copyOf(merged.values());
    }
}
