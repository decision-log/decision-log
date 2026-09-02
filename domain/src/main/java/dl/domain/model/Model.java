package dl.domain.model;

import dl.domain.ports.SttPort.Utterance;

import java.time.Instant;
import java.util.List;

public final class Model {
    public record MeetingId(String value) {}
    public record IssueId(String value) {}
    public record TranscriptId(String value) {}
    public record ExtractionId(String value) {}

    /**
     * 후보 ID 는 이슈 ID 와 <b>다른 타입이고 값도 겹치지 않는다.</b>
     * 타입만 가르면 컴파일러는 막지만 문자열로 왕복하는 경계(HTTP · JSON)는 못 막는다 —
     * 값이 안 겹치면 둘을 바꿔 넣어도 조회가 빈다.
     */
    public record CandidateId(String value) {}
    public record OpinionId(String value) {}
    public record TaskId(String value) {}
    public record TermCandidateId(String value) {}

    public enum State { 쟁점, 결정, 실행됨, 무효 }

    /**
     * 추출이 <b>제안</b>할 수 있는 상태는 둘뿐이다. {@code 무효} 와 {@code 실행됨} 은 타입에 없다 —
     * 회의록에서 판단할 신호가 거의 없고 사람이 고친다 (seams.md ⓶).
     *
     * <p>상수 이름이 곧 저장 값이라 한국어로 남는다 (ADR 0006 의 예외 둘 중 하나).
     */
    public enum ProposedState { 쟁점, 결정 }

    /**
     * 회의록 한 벌 — 회의 하나가 여러 개를 갖는다 (seams.md ⓷).
     * 여러 개인 이유는 벌(vintage)이 아니라 <b>덩이</b>다.
     */
    public record Transcript(TranscriptId id, List<Utterance> utterances) {
        public Transcript { utterances = List.copyOf(utterances); }
    }

    /**
     * 이 벌을 만든 설정의 지문. 결과와 <b>분리</b>돼 있다 —
     * 한 회의에 여러 벌을 나란히 놓고 판정하는 것이 이 넷의 용도다.
     */
    public record Meta(String modelName, String promptVersion, String promptHash, TokenUsage tokens) {}

    /**
     * 토큰수는 통째로 optional 이다 — 모델을 안 부르는 구현체는 {@code 0} 이 아니라 비운다.
     * {@code 0} 은 비교표에서 "제일 싸다"로 읽힌다.
     *
     * <p><b>여기서 거부하는 것은 음수뿐이고 {@code 0} 은 지난다.</b> 거짓 0 을 잡는 것은
     * 계약 ⑤ 메타정직의 몫이라, 위반을 표현할 수 있어야 그 검사가 무언가를 잰다.
     */
    public record TokenUsage(long input, long output, long cacheHit) {
        public TokenUsage {
            if (input < 0 || output < 0 || cacheHit < 0)
                throw new IllegalArgumentException("토큰수가 음수다: %d/%d/%d".formatted(input, output, cacheHit));
        }
    }

    /** 근거가 가리키는 회의록의 한 지점. 전역 구간 번호는 저장 경계에서 이걸로 해소된다. */
    public record Evidence(TranscriptId transcript, int seq) {}

    /** 아직 이슈가 아니다 — 추적 대상이 아니므로 따로 산다 (seams.md) */
    public record IssueCandidate(CandidateId id, ExtractionId extraction, Content content, List<Evidence> evidence) {
        public IssueCandidate {
            if (evidence.isEmpty())
                throw new IllegalArgumentException("근거 없는 이슈 후보다 — 확인 화면에서 사람이 판단할 재료가 0 이다");
            evidence = List.copyOf(evidence);
        }

        /**
         * 포트와 저장이 같은 이 레코드를 쓴다 — 필드가 두 군데면 한 군데만 자란다.
         * 참조와 구간이 여기 없는 것은 그 둘만 층마다 타입이 다르기 때문이다 (#5 합의문).
         */
        public record Content(String question, ProposedState state, String answer, String undecidedReason) {
            /** 도메인 내부 경로를 지킨다. <b>null 짝맞춤만 본다</b> — blank 까지 보는 것은 계약 ⑥ 이다. */
            public Content {
                var decided = state == ProposedState.결정;
                if (decided != (answer != null))
                    throw new IllegalArgumentException("결정 ⟺ 답 있음이 깨졌다: 상태 %s · 답 %s".formatted(state, answer));
                if (decided && undecidedReason != null)
                    throw new IllegalArgumentException("미결정사유는 쟁점에만 붙는다: " + undecidedReason);
            }

            /**
             * 추출기가 쓰는 입구 — 버리지 않고 맞춘다.
             *
             * <p><b>답이 이긴다.</b> 상태를 살리고 답을 버리면 그게 누락이고, 반대로 결정으로
             * 올리는 것은 과잉이라 확인 화면에서 클릭 한 번에 되돌아온다. 그래서 넘어온
             * {@code state} 는 판정에 안 쓰인다 — 무엇이 이기는지는 어댑터가 아니라 도메인이 정한다.
             */
            public static Content lenient(String question, ProposedState state, String answer, String reason) {
                var given = blankToNull(answer);
                return given != null
                        ? new Content(question, ProposedState.결정, given, null)
                        : new Content(question, ProposedState.쟁점, null, blankToNull(reason));
            }

            private static String blankToNull(String s) {
                return s == null || s.isBlank() ? null : s;
            }
        }
    }

    public record Issue(IssueId id, String question, State state, String answer) {}

    public record Term(String spelling, String meaning) {}

    public record TermCandidate(TermCandidateId id, ExtractionId extraction, Content content, List<Evidence> evidence) {
        public TermCandidate {
            if (evidence.isEmpty())
                throw new IllegalArgumentException("근거 없는 용어 후보다 — 깨진 표기 옆에 그 말이 나온 회의록 한 줄이 없다");
            evidence = List.copyOf(evidence);
        }

        public record Content(String spelling, String meaning) {}
    }

    /**
     * 근거의 일부다. 화면은 이번 범위에 없지만 뽑아서 저장한다 —
     * 호출 비용이 같고 나중에 화면 붙일 때 데이터가 이미 있다 (seams.md ⓶).
     *
     * <p>{@code issue} 는 optional 이다. 어느 이슈에도 안 붙는 의견이 실제로 나온다.
     */
    public record Opinion(OpinionId id, ExtractionId extraction, CandidateId issue,
                          Content content, Evidence evidence) {
        public Opinion {
            if (evidence == null) throw new IllegalArgumentException("근거 없는 의견이다");
        }

        public record Content(String speakerLabel, String text) {}
    }

    /** 결정에서 도출된 작업. 담당자표기는 optional 이다 — 대본이 담당자를 안 적을 수 있다. */
    public record Task(TaskId id, ExtractionId extraction, CandidateId issue,
                       Content content, Evidence evidence) {
        public Task {
            if (evidence == null) throw new IllegalArgumentException("근거 없는 할 일이다");
        }

        public record Content(String text, String assignee) {}
    }

    /**
     * 벌 하나 — 한 회의에 여러 벌이 병존한다 (seams.md ⓶).
     * 재처리는 이전 벌을 지우지 않고 회의의 <i>현재 벌</i> 포인터를 옮긴다.
     */
    public record Extraction(ExtractionId id, MeetingId meeting, Meta meta, Instant createdAt,
                             List<IssueCandidate> issueCandidates, List<Opinion> opinions,
                             List<Task> tasks, List<TermCandidate> termCandidates) {
        public Extraction {
            issueCandidates = List.copyOf(issueCandidates);
            opinions = List.copyOf(opinions);
            tasks = List.copyOf(tasks);
            termCandidates = List.copyOf(termCandidates);
        }
    }
}
