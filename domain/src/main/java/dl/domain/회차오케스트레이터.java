package dl.domain;

import dl.domain.model.Model.*;
import dl.domain.ports.*;
import dl.domain.ports.SttPort.*;
import dl.domain.ports.저장소.*;

import java.util.*;

/**
 * 한 회차가 도는 순서. 회차 시뮬레이터와 애플리케이션이 **같은 것을** 돈다.
 *
 * 프레임워크를 모른다. 트랜잭션도 모른다 — 아는 것은 "여기부터 여기까지가 한 단위"뿐이고,
 * 그걸 {@link 단위작업} 에 말한다.
 */
public final class 회차오케스트레이터 {

    private final SttPort stt;
    private final ExtractPort extract;
    private final 회의저장소 회의들;
    private final 이슈저장소 이슈들;
    private final 용어집저장소 용어집;
    private final 단위작업 단위;

    public 회차오케스트레이터(SttPort stt, ExtractPort extract,
                              회의저장소 회의들, 이슈저장소 이슈들, 용어집저장소 용어집, 단위작업 단위) {
        this.stt = stt; this.extract = extract;
        this.회의들 = 회의들; this.이슈들 = 이슈들; this.용어집 = 용어집; this.단위 = 단위;
    }

    /**
     * 우선순위 순으로 전량을 넘긴다. 한도까지 자르는 것은 어댑터의 일이다 (seams.md ⓵).
     */
    public List<ContextItem> 컨텍스트조립() {
        var 이슈전량 = 이슈들.전량();
        var out = new ArrayList<ContextItem>();

        for (var t : 용어집.전량())
            out.add(new ContextItem(ContextKind.용어, t.표기(), t.뜻()));
        for (var i : 이슈전량)
            if (i.상태() == 상태.쟁점) out.add(new ContextItem(ContextKind.후보쟁점, i.질문(), null));
        for (var i : 이슈전량)
            if (i.상태() != 상태.쟁점 && i.상태() != 상태.무효)
                out.add(new ContextItem(ContextKind.이슈, i.질문(), i.답()));
        return out;
    }

    public record 회차결과(회의ID 회의, List<Utterance> 회의록, List<이슈후보> 후보, List<용어후보> 용어후보) {}

    /**
     * 오디오 하나가 회의록과 후보가 된다.
     *
     * 전사와 추출이 각각 수 분 걸리므로 저장은 두 섬으로 갈린다.
     * 긴 외부 호출을 트랜잭션 안에 두면 커넥션을 그동안 붙잡게 된다.
     */
    public 회차결과 돈다(Audio audio, String 프롬프트버전) {
        var ctx = 컨텍스트조립();
        var job = stt.전사요청(audio, ctx);
        var 전사 = stt.결과(job);

        var 회의 = 단위.안에서(() -> {
            var m = 회의들.새회의();
            회의들.회의록저장(m, 전사.회의록());
            return m;
        });

        var 추출 = extract.추출(전사.회의록(), 프롬프트버전);

        var 후보들 = 추출.이슈후보().stream()
                .map(d -> new 이슈후보(new 이슈ID(UUID.randomUUID().toString()), 회의,
                                       d.질문(), d.상태(), d.답(), d.근거구간()))
                .toList();
        단위.안에서(() -> 이슈들.후보저장(후보들));

        return new 회차결과(회의, 전사.회의록(), 후보들, 추출.용어후보());
    }

    /**
     * 확인 — 후보를 이슈로, 용어 후보를 용어집으로. 회의 참석자가 함께 한다.
     *
     * 둘은 한 단위다. 절반만 확인된 회의가 남으면 다음 회차 컨텍스트로 그대로 나간다.
     */
    public void 확인(List<이슈ID> 승격할후보, List<용어후보> 승격할용어) {
        var 용어들 = 표기로_추린다(승격할용어);
        단위.안에서(() -> {
            이슈들.승격(승격할후보);
            용어집.추가(용어들);
        });
    }

    /** 같은 표기가 한 회의에서 여러 번 나올 수 있다. 먼저 나온 것을 남긴다. */
    private static List<용어> 표기로_추린다(List<용어후보> 후보) {
        var m = new LinkedHashMap<String, 용어>();
        for (var t : 후보) m.putIfAbsent(t.표기(), new 용어(t.표기(), t.뜻()));
        return List.copyOf(m.values());
    }
}
