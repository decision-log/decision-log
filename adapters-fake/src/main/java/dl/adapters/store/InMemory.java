package dl.adapters.store;

import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Utterance;
import dl.domain.ports.UnitOfWork;
import dl.domain.ports.Stores.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * 회차 시뮬레이터용 저장소.
 *
 * 단위작업이 스냅샷/복원으로 **진짜 롤백**을 한다. no-op 으로 두면 시뮬레이터가
 * 원자성에 대해 아무것도 증명하지 못하고, 절반만 확인된 회의가 여기선 안 보인다.
 */
public final class InMemory {

    public interface Revertible { Object snapshot(); void restore(Object s); }

    public static final class Meetings implements MeetingStore, Revertible {
        final Map<String, List<Transcript>> transcripts = new LinkedHashMap<>();

        public MeetingId newMeeting() {
            var id = new MeetingId(UUID.randomUUID().toString());
            transcripts.put(id.value(), List.of());
            return id;
        }

        /** 덧붙인다 — 회의 하나가 회의록 여러 개를 갖는다. 덩이마다 자기 ID 를 받는다. */
        public TranscriptId saveTranscript(MeetingId m, List<Utterance> us) {
            var chunks = new ArrayList<>(transcripts.getOrDefault(m.value(), List.of()));
            var id = new TranscriptId(UUID.randomUUID().toString());
            chunks.add(new Transcript(id, us));
            transcripts.put(m.value(), List.copyOf(chunks));
            return id;
        }

        /** 넣은 순서가 곧 덩이 순서다. */
        public List<Transcript> fullTranscript(MeetingId m) {
            return transcripts.getOrDefault(m.value(), List.of());
        }

        public Object snapshot() { return new LinkedHashMap<>(transcripts); }
        @SuppressWarnings("unchecked")
        public void restore(Object s) { transcripts.clear(); transcripts.putAll((Map<String, List<Transcript>>) s); }
    }

    /**
     * 벌이 쌓이고 회의는 <b>현재 벌 하나</b>를 가리킨다 — 재처리가 이전 벌을 지우지 않는다.
     * 미확인 후보를 현재 벌에서만 주는 것이 벌 셋 병존 시 후보가 3배로 뜨는 것을 막는 자리다.
     */
    public static final class Extractions implements ExtractionStore, Revertible {
        final Map<String, Extraction> extractions = new LinkedHashMap<>();
        final Map<String, String> currentExtraction = new LinkedHashMap<>();   // 회의 → 현재 벌
        final Map<String, String> promotedIssueId = new LinkedHashMap<>();     // 후보 → 올라간 이슈

        public void save(Extraction extraction) {
            extractions.put(extraction.id().value(), extraction);
            currentExtraction.put(extraction.meeting().value(), extraction.id().value());
        }

        public List<IssueCandidate> unconfirmedCandidates(MeetingId meeting) {
            return current(meeting)
                    .map(e -> e.issueCandidates().stream().filter(c -> !isPromoted(c.id())).toList())
                    .orElse(List.of());
        }

        public List<TermCandidate> termCandidates(MeetingId meeting) {
            return current(meeting).map(Extraction::termCandidates).orElse(List.of());
        }

        private Optional<Extraction> current(MeetingId meeting) {
            return Optional.ofNullable(currentExtraction.get(meeting.value())).map(extractions::get);
        }

        /** 후보를 이슈로 <b>만드는</b> 것은 이슈저장소의 일이라 여기선 찾아 주고 표시만 한다. */
        Optional<IssueCandidate> candidate(CandidateId id) {
            return extractions.values().stream()
                    .flatMap(e -> e.issueCandidates().stream())
                    .filter(c -> c.id().equals(id))
                    .findFirst();
        }

        boolean isPromoted(CandidateId id) { return promotedIssueId.containsKey(id.value()); }

        void markPromoted(CandidateId candidate, IssueId issue) {
            promotedIssueId.put(candidate.value(), issue.value());
        }

        public Object snapshot() {
            return List.of(new LinkedHashMap<>(extractions), new LinkedHashMap<>(currentExtraction),
                    new LinkedHashMap<>(promotedIssueId));
        }

        @SuppressWarnings("unchecked")
        public void restore(Object s) {
            var l = (List<Map<String, ?>>) s;
            extractions.clear(); extractions.putAll((Map<String, Extraction>) l.get(0));
            currentExtraction.clear(); currentExtraction.putAll((Map<String, String>) l.get(1));
            promotedIssueId.clear(); promotedIssueId.putAll((Map<String, String>) l.get(2));
        }
    }

    public static final class Issues implements IssueStore, Revertible {
        private final Extractions extractions;
        final Map<String, Issue> issues = new LinkedHashMap<>();

        /** 후보가 사는 곳이 추출저장소라 승격은 둘을 함께 본다. */
        public Issues(Extractions extractions) { this.extractions = extractions; }

        /** 후보는 <b>새 이슈 ID</b> 를 받는다 — 타입만이 아니라 값까지 가른다. */
        public void promote(List<CandidateId> ids) {
            for (var id : ids) {
                if (extractions.isPromoted(id)) continue;
                extractions.candidate(id).ifPresent(c -> {
                    var issueId = new IssueId(UUID.randomUUID().toString());
                    var content = c.content();
                    issues.put(issueId.value(), new Issue(issueId, content.question(),
                            State.valueOf(content.state().name()), content.answer()));
                    extractions.markPromoted(id, issueId);
                });
            }
        }

        public List<Issue> all() { return List.copyOf(issues.values()); }
        public Optional<Issue> find(IssueId id) { return Optional.ofNullable(issues.get(id.value())); }

        public Object snapshot() { return new LinkedHashMap<>(issues); }
        @SuppressWarnings("unchecked")
        public void restore(Object s) { issues.clear(); issues.putAll((Map<String, Issue>) s); }
    }

    public static final class Glossary implements GlossaryStore, Revertible {
        final Map<String, Term> m = new LinkedHashMap<>();

        public void add(List<Term> terms) { for (var t : terms) m.putIfAbsent(t.spelling(), t); }
        public List<Term> all() { return List.copyOf(m.values()); }

        public void edit(String oldSpelling, String newSpelling, String newMeaning) {
            if (!m.containsKey(oldSpelling)) throw new NoSuchElementException(oldSpelling);
            if (!oldSpelling.equals(newSpelling) && m.containsKey(newSpelling)) throw new SpellingConflict(newSpelling);
            m.remove(oldSpelling);
            m.put(newSpelling, new Term(newSpelling, newMeaning));
        }

        public Object snapshot() { return new LinkedHashMap<>(m); }
        @SuppressWarnings("unchecked")
        public void restore(Object s) { m.clear(); m.putAll((Map<String, Term>) s); }
    }

    /** 스냅샷 → 실행 → 터지면 복원. 중첩은 가장 바깥이 관리한다. */
    public static final class Unit implements UnitOfWork {
        private final List<Revertible> targets;
        private int depth = 0;

        public Unit(Revertible... xs) { this.targets = List.of(xs); }

        @Override public <T> T within(Supplier<T> block) {
            if (depth++ > 0) {
                try { return block.get(); } finally { depth--; }
            }
            var snap = targets.stream().map(Revertible::snapshot).toList();
            try {
                return block.get();
            } catch (RuntimeException e) {
                for (int i = 0; i < targets.size(); i++) targets.get(i).restore(snap.get(i));
                throw e;
            } finally { depth--; }
        }
    }
}
