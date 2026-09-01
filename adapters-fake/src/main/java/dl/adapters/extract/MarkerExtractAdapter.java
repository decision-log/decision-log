package dl.adapters.extract;

import dl.domain.model.Model.*;
import dl.domain.ports.ExtractPort;
import dl.domain.ports.SttPort.Utterance;

import java.util.*;

/** ADR 0005 — 마커 대본. 대본에 정답을 달아두고 그걸 읽는다. LLM 안 부른다. */
public final class MarkerExtractAdapter implements ExtractPort {

    /** 발화 텍스트에 붙은 마커: «이슈:질문» «용어:표기» */
    @Override
    public ExtractionResult extract(List<Utterance> transcript, String promptVersion) {
        var issues = new ArrayList<IssueCandidateDraft>();
        var terms = new ArrayList<TermCandidate>();
        for (int i = 0; i < transcript.size(); i++) {
            var t = transcript.get(i).text();
            for (var m : marker(t, "이슈")) issues.add(new IssueCandidateDraft(m, State.쟁점, null, List.of(i)));
            for (var m : marker(t, "용어")) terms.add(new TermCandidate(m, null, null));
        }
        return new ExtractionResult(issues, terms, "marker-fake", "v" + promptVersion);
    }

    private static List<String> marker(String s, String kind) {
        var out = new ArrayList<String>();
        var p = java.util.regex.Pattern.compile("«" + kind + ":([^»]+)»");
        var m = p.matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
