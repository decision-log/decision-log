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
         */
        void saveTranscript(MeetingId meeting, List<SttPort.Utterance> transcript);
    }

    interface IssueStore {
        void saveCandidates(List<IssueCandidate> candidates);
        List<IssueCandidate> unconfirmedCandidates(MeetingId meeting);

        /** 후보를 이슈로 올린다. 이미 올라간 것은 무시한다. */
        void promote(List<IssueId> ids);

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
         * 새표기가 기존표기와 다른데 이미 있으면 {@link 표기충돌} — 병합은 삭제의 다른 얼굴이라 거부한다.
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
