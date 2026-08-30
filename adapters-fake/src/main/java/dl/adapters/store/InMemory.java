package dl.adapters.store;

import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Utterance;
import dl.domain.ports.단위작업;
import dl.domain.ports.저장소.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * 회차 시뮬레이터용 저장소.
 *
 * 단위작업이 스냅샷/복원으로 **진짜 롤백**을 한다. no-op 으로 두면 시뮬레이터가
 * 원자성에 대해 아무것도 증명하지 못하고, 절반만 확인된 회의가 여기선 안 보인다.
 */
public final class InMemory {

    public interface 되돌릴수있음 { Object 스냅샷(); void 복원(Object s); }

    public static final class 회의들 implements 회의저장소, 되돌릴수있음 {
        final Map<String, List<Utterance>> 회의록 = new LinkedHashMap<>();

        public 회의ID 새회의() {
            var id = new 회의ID(UUID.randomUUID().toString());
            회의록.put(id.value(), List.of());
            return id;
        }
        public void 회의록저장(회의ID m, List<Utterance> us) { 회의록.put(m.value(), List.copyOf(us)); }

        public Object 스냅샷() { return new LinkedHashMap<>(회의록); }
        @SuppressWarnings("unchecked")
        public void 복원(Object s) { 회의록.clear(); 회의록.putAll((Map<String, List<Utterance>>) s); }
    }

    public static final class 이슈들 implements 이슈저장소, 되돌릴수있음 {
        final Map<String, 이슈후보> 후보 = new LinkedHashMap<>();
        final Map<String, 이슈> 이슈 = new LinkedHashMap<>();

        public void 후보저장(List<이슈후보> 후보들) { for (var c : 후보들) 후보.put(c.id().value(), c); }

        public List<이슈후보> 미확인후보(회의ID m) {
            return 후보.values().stream()
                    .filter(c -> c.회의().equals(m) && !이슈.containsKey(c.id().value()))
                    .toList();
        }

        public void 승격(List<이슈ID> ids) {
            for (var id : ids) {
                var c = 후보.get(id.value());
                if (c != null) 이슈.putIfAbsent(id.value(), new 이슈(id, c.질문(), c.상태(), c.답()));
            }
        }

        public List<이슈> 전량() { return List.copyOf(이슈.values()); }
        public Optional<이슈> 찾기(이슈ID id) { return Optional.ofNullable(이슈.get(id.value())); }

        public Object 스냅샷() { return List.of(new LinkedHashMap<>(후보), new LinkedHashMap<>(이슈)); }
        @SuppressWarnings("unchecked")
        public void 복원(Object s) {
            var l = (List<Map<String, ?>>) s;
            후보.clear(); 후보.putAll((Map<String, 이슈후보>) l.get(0));
            이슈.clear(); 이슈.putAll((Map<String, 이슈>) l.get(1));
        }
    }

    public static final class 용어집 implements 용어집저장소, 되돌릴수있음 {
        final Map<String, 용어> m = new LinkedHashMap<>();

        public void 추가(List<용어> 용어들) { for (var t : 용어들) m.putIfAbsent(t.표기(), t); }
        public List<용어> 전량() { return List.copyOf(m.values()); }

        public void 수정(String 기존표기, String 새표기, String 새뜻) {
            if (!m.containsKey(기존표기)) throw new NoSuchElementException(기존표기);
            if (!기존표기.equals(새표기) && m.containsKey(새표기)) throw new 표기충돌(새표기);
            m.remove(기존표기);
            m.put(새표기, new 용어(새표기, 새뜻));
        }

        public Object 스냅샷() { return new LinkedHashMap<>(m); }
        @SuppressWarnings("unchecked")
        public void 복원(Object s) { m.clear(); m.putAll((Map<String, 용어>) s); }
    }

    /** 스냅샷 → 실행 → 터지면 복원. 중첩은 가장 바깥이 관리한다. */
    public static final class 단위 implements 단위작업 {
        private final List<되돌릴수있음> 대상;
        private int 깊이 = 0;

        public 단위(되돌릴수있음... xs) { this.대상 = List.of(xs); }

        @Override public <T> T 안에서(Supplier<T> 묶음) {
            if (깊이++ > 0) {
                try { return 묶음.get(); } finally { 깊이--; }
            }
            var 스냅 = 대상.stream().map(되돌릴수있음::스냅샷).toList();
            try {
                return 묶음.get();
            } catch (RuntimeException e) {
                for (int i = 0; i < 대상.size(); i++) 대상.get(i).복원(스냅.get(i));
                throw e;
            } finally { 깊이--; }
        }
    }
}
