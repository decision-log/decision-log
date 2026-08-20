package dl.adapters.extract;

import dl.domain.model.Model.*;
import dl.domain.ports.ExtractPort;
import dl.domain.ports.SttPort.Utterance;

import java.util.*;

/** ADR 0005 — 마커 대본. 대본에 정답을 달아두고 그걸 읽는다. LLM 안 부른다. */
public final class MarkerExtractAdapter implements ExtractPort {

    /** 발화 텍스트에 붙은 마커: «이슈:질문» «용어:표기» */
    @Override
    public 추출결과 추출(List<Utterance> 회의록, String 프롬프트버전) {
        var 이슈 = new ArrayList<이슈후보초안>();
        var 용어 = new ArrayList<용어후보>();
        for (int i = 0; i < 회의록.size(); i++) {
            var t = 회의록.get(i).text();
            for (var m : 마커(t, "이슈")) 이슈.add(new 이슈후보초안(m, 상태.쟁점, null, List.of(i)));
            for (var m : 마커(t, "용어")) 용어.add(new 용어후보(m, null, null));
        }
        return new 추출결과(이슈, 용어, "marker-fake", "v" + 프롬프트버전);
    }

    private static List<String> 마커(String s, String kind) {
        var out = new ArrayList<String>();
        var p = java.util.regex.Pattern.compile("«" + kind + ":([^»]+)»");
        var m = p.matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
