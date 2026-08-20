package dl.domain.ports;

import dl.domain.model.Model.*;
import java.util.List;

public interface ExtractPort {
    추출결과 추출(List<SttPort.Utterance> 회의록, String 프롬프트버전);

    record 추출결과(List<이슈후보초안> 이슈후보, List<용어후보> 용어후보, String 모델명, String 프롬프트해시) {}
    record 이슈후보초안(String 질문, 상태 상태, String 답, List<Integer> 근거구간) {}
}
