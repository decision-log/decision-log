package dl.sim;

import dl.adapters.extract.MarkerExtractAdapter;
import dl.adapters.store.InMemory;
import dl.adapters.stt.SimulatorSttAdapter;
import dl.adapters.stt.SimulatorSttAdapter.Mode;
import dl.adapters.stt.SimulatorSttAdapter.Rule;
import dl.domain.회차오케스트레이터;
import dl.domain.model.Model.*;
import dl.domain.ports.SttPort.Audio;

import java.util.*;

/** 회차를 돈다. 사슬 로직은 전부 domain 의 오케스트레이터가 갖는다. */
public final class RoundSimulator {

    public static void main(String[] args) {
        var 대본 = List.of(
                "리버스 프록시는 «용어:Caddy»로 가죠, «이슈:리버스 프록시를 무엇으로 할 것인가»",
                "«용어:툴 콜링»을 어떻게 처리할지 «이슈:툴 콜링 실패를 어떻게 처리할 것인가»",
                "«용어:스크럼»은 매일 아침에 합니다");

        var 규칙 = List.of(
                new Rule("Caddy",   Mode.일관,   List.of("캐디")),
                new Rule("툴 콜링", Mode.흔들림, List.of("툴 풀링", "풀 콜링", "툴 콜딩")),
                new Rule("스크럼",  Mode.일관,   List.of("시끄러움")));

        // 확인 화면에서 사람이 하는 일: 깨진 표기를 정답으로 고친다
        var 사람의교정 = new HashMap<String, String>();
        for (Rule r : 규칙) for (String 깨진 : r.오염형()) 사람의교정.put(깨진, r.정답());

        var 회의들 = new InMemory.회의들();
        var 이슈들 = new InMemory.이슈들();
        var 용어집 = new InMemory.용어집();

        long t0 = System.nanoTime();
        for (int 회차 = 1; 회차 <= 3; 회차++) {
            var stt = new SimulatorSttAdapter(대본, 규칙, 7L);
            var o = new 회차오케스트레이터(stt, new MarkerExtractAdapter(), 회의들, 이슈들, 용어집, new InMemory.단위(회의들, 이슈들, 용어집));

            var r = o.돈다(new Audio(new byte[0], "meeting.mp3"), "1");

            System.out.println("── " + 회차 + "회차  (용어집 " + 용어집.전량().size()
                    + "건 → 컨텍스트 " + o.컨텍스트조립().size() + "항목)");
            r.회의록().forEach(u -> System.out.println("     " + u.text()));
            System.out.println("   이슈후보: " + r.후보().stream().map(이슈후보::질문).toList());
            System.out.println("   용어후보: " + r.용어후보().stream().map(용어후보::표기).toList());

            // 확인 — 후보 전량 승격, 용어는 표기를 고쳐서 승격
            var 고친용어 = r.용어후보().stream()
                    .map(t -> new 용어후보(사람의교정.getOrDefault(t.표기(), t.표기()), t.뜻(), t.회의()))
                    .toList();
            o.확인(r.후보().stream().map(이슈후보::id).toList(), 고친용어);
            System.out.println("   확인 후 용어집: " + 용어집.전량().stream().map(용어::표기).toList());
            System.out.println();
        }
        System.out.printf("3회차 %d ms%n", (System.nanoTime() - t0) / 1_000_000);
    }
}
