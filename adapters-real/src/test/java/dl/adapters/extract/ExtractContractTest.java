package dl.adapters.extract;

import dl.domain.ports.ExtractPort;
import dl.domain.ports.ExtractPort.ExtractionResult;
import dl.domain.ports.SttPort.Utterance;
import dl.script.Scripts;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** 어댑터 세 벌이 전부 통과해야 하는 계약. Spring 없음. */
abstract class ExtractContractTest {

    static final String PROMPT_VERSION = "1";

    /** 대본 줄을 발화로 — 화자 라벨은 비우고(화자 분리를 안 한다) 구간은 3초씩이다. */
    static List<Utterance> asUtterances(List<String> lines) {
        var out = new ArrayList<Utterance>();
        for (int i = 0; i < lines.size(); i++)
            out.add(new Utterance(null, i * 3.0, i * 3.0 + 3.0, lines.get(i)));
        return List.copyOf(out);
    }

    /** 기본 입력은 마커 대본이다. 마커를 모르는 어댑터는 이 줄들을 그냥 발화로 읽는다. */
    static final List<Utterance> DEFAULT_TRANSCRIPT = asUtterances(Scripts.lines(Scripts.defaultScript()));

    abstract ExtractPort adapter();

    List<Utterance> transcript() { return DEFAULT_TRANSCRIPT; }

    private ExtractionResult result() { return adapter().extract(transcript(), PROMPT_VERSION); }

    @Test
    void 안_풀리는_참조를_남기지_않는다() {
        ExtractContractChecks.referentialIntegrity(result());
    }

    @Test
    void 로컬키가_결과_안에서_유일하다() {
        ExtractContractChecks.uniqueLocalKeys(result());
    }

    @Test
    void 근거가_회의록_안을_가리킨다() {
        ExtractContractChecks.spansInRange(result(), transcript().size());
    }

    @Test
    void 같은_표기의_용어_후보가_한_건이다() {
        ExtractContractChecks.uniqueTermSpellings(result());
    }

    @Test
    void 메타가_정직하다() {
        ExtractContractChecks.honestMeta(result(), PROMPT_VERSION);
    }

    @Test
    void 상태와_답이_어긋나지_않는다() {
        ExtractContractChecks.stateAnswerAgreement(result());
    }
}
