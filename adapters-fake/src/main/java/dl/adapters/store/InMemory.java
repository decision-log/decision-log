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
        final Map<String, List<Utterance>> transcripts = new LinkedHashMap<>();

        public MeetingId newMeeting() {
            var id = new MeetingId(UUID.randomUUID().toString());
            transcripts.put(id.value(), List.of());
            return id;
        }
        /** 덧붙인다 — 회의 하나가 회의록 여러 개를 갖는다. 덩이 경계는 읽는 쪽이 없어 안 남긴다. */
        public void saveTranscript(MeetingId m, List<Utterance> us) {
            var merged = new ArrayList<>(transcripts.getOrDefault(m.value(), List.of()));
            merged.addAll(us);
            transcripts.put(m.value(), List.copyOf(merged));
        }

        public Object snapshot() { return new LinkedHashMap<>(transcripts); }
        @SuppressWarnings("unchecked")
        public void restore(Object s) { transcripts.clear(); transcripts.putAll((Map<String, List<Utterance>>) s); }
    }

    public static final class Issues implements IssueStore, Revertible {
        final Map<String, IssueCandidate> candidate = new LinkedHashMap<>();
        final Map<String, Issue> issues = new LinkedHashMap<>();

        public void saveCandidates(List<IssueCandidate> candidates) { for (var c : candidates) candidate.put(c.id().value(), c); }

        public List<IssueCandidate> unconfirmedCandidates(MeetingId m) {
            return candidate.values().stream()
                    .filter(c -> c.meeting().equals(m) && !issues.containsKey(c.id().value()))
                    .toList();
        }

        public void promote(List<IssueId> ids) {
            for (var id : ids) {
                var c = candidate.get(id.value());
                if (c != null) issues.putIfAbsent(id.value(), new Issue(id, c.question(), c.state(), c.answer()));
            }
        }

        public List<Issue> all() { return List.copyOf(issues.values()); }
        public Optional<Issue> find(IssueId id) { return Optional.ofNullable(issues.get(id.value())); }

        public Object snapshot() { return List.of(new LinkedHashMap<>(candidate), new LinkedHashMap<>(issues)); }
        @SuppressWarnings("unchecked")
        public void restore(Object s) {
            var l = (List<Map<String, ?>>) s;
            candidate.clear(); candidate.putAll((Map<String, IssueCandidate>) l.get(0));
            issues.clear(); issues.putAll((Map<String, Issue>) l.get(1));
        }
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
