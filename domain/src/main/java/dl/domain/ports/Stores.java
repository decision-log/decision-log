package dl.domain.ports;

import dl.domain.model.Model.*;
import java.util.List;
import java.util.Optional;

/**
 * 저장 경계.
 *
 * 쓰기는 전부 묶음으로 받는다. 하나씩 받으면 저장소가 집합 연산을 못 쓰고,
 * 도메인이 루프를 도는 동안 왕복이 그만큼 늘어난다.
 */
public interface Stores {

    interface MeetingStore {
        /**
         * 회차오케스트레이터는 이제 이걸 안 부른다 — 회의는 사람이 먼저 만든다.
         * 회차 시뮬레이터처럼 사람 없이 회차를 도는 쪽에만 남은 길이다.
         */
        MeetingId newMeeting();

        /**
         * 회의록 한 벌을 <b>덧붙인다.</b> 회의 하나가 회의록 여러 개를 갖는다 (seams.md ⓷) —
         * 대기가 길면 사람이 논의를 두 덩이로 끊기 때문이고, 합칠 때는 넣은 순서다.
         * v0.5 화면은 1:1 로만 쓴다.
         *
         * <p>만든 회의록의 ID 를 돌려준다 — 근거가 그 ID 로 회의록의 한 지점을 가리킨다.
         */
        TranscriptId saveTranscript(MeetingId meeting, List<SttPort.Utterance> transcript);

        /**
         * 회의록 전량을 덩이 순서대로. 추출은 이걸 이어붙여 <b>한 번</b> 돈다 —
         * 한 이슈가 전반·후반에 걸칠 수 있어 덩이별로 돌면 경계에서 갈라진다.
         *
         * <p>저장된 것이 정본이다. 왕복 한 번이 늘지만 6명 규모에서 잴 수 없고,
         * 잡이 추출만 다시 돌 때도 같은 길이다.
         */
        List<Transcript> fullTranscript(MeetingId meeting);
    }

    /**
     * 벌 하나가 한 단위다.
     *
     * <p>이슈저장소와 갈린 이유는 이슈가 추적 대상이고 후보·의견·할 일·용어후보는 넷 다
     * 아니기 때문이다 — {@code CONTEXT.md} 가 만든 그 구분이 포트 경계와 그대로 맞는다.
     */
    interface ExtractionStore {
        /** 벌 하나를 통째로. 회의의 현재 벌 포인터도 여기서 옮긴다. */
        void save(Extraction extraction);

        /**
         * <b>현재 벌의</b> 미확인 후보만. 벌이 셋 병존할 때 전량을 주면 같은 회의록에서 뽑은
         * 후보가 3배로 뜨고 컨텍스트 조립에도 3배로 들어간다 — 조용히 깨지는 자리다.
         */
        List<IssueCandidate> unconfirmedCandidates(MeetingId meeting);

        /** 현재 벌의 용어 후보. */
        List<TermCandidate> termCandidates(MeetingId meeting);
    }

    interface IssueStore {
        /**
         * 후보를 이슈로 올린다. 이미 올라간 것은 무시한다.
         *
         * <p><b>후보는 새 이슈 ID 를 받는다</b> — 타입만 가르지 않고 값까지 가른다.
         * 문자열로 왕복하는 경계에서 둘을 바꿔 넣으면 조회가 빈다.
         */
        void promote(List<CandidateId> ids);

        List<Issue> all();
        Optional<Issue> find(IssueId id);
    }

    interface GlossaryStore {
        /** 같은 표기가 이미 있으면 무시한다 — 존재 여부를 따로 묻지 않는다. */
        void add(List<Term> terms);
        List<Term> all();

        /**
         * 제자리 교체. 이력은 남기지 않는다.
         *
         * 기존표기가 없으면 {@link java.util.NoSuchElementException}.
         * 새표기가 기존표기와 다른데 이미 있으면 {@link SpellingConflict} — 병합은 삭제의 다른 얼굴이라 거부한다.
         * 기존표기와 새표기가 같으면 뜻만 갈아끼운다. 새뜻은 null 일 수 있다(뜻 비우기).
         */
        void edit(String oldSpelling, String newSpelling, String newMeaning);
    }

    /** 표기는 용어집의 열쇠다 — 이미 있는 표기로는 못 옮긴다. */
    class SpellingConflict extends RuntimeException {
        public SpellingConflict(String spelling) { super("이미 있는 표기입니다: " + spelling); }
    }

    interface RosterStore {
        /**
         * 목록 통째 교체. 추가·삭제·이름 고치기가 전부 이 하나로 수렴한다.
         * 이름은 호출자가 다듬고 중복을 걸러 넘긴다.
         */
        void saveRoster(List<String> names);

        /** 순서는 의미를 갖지 않는다 — 이름순으로 준다. */
        List<String> roster();
    }
}
