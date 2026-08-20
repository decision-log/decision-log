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
public interface 저장소 {

    interface 회의저장소 {
        회의ID 새회의();
        void 회의록저장(회의ID 회의, List<SttPort.Utterance> 회의록);
    }

    interface 이슈저장소 {
        void 후보저장(List<이슈후보> 후보들);
        List<이슈후보> 미확인후보(회의ID 회의);

        /** 후보를 이슈로 올린다. 이미 올라간 것은 무시한다. */
        void 승격(List<이슈ID> ids);

        List<이슈> 전량();
        Optional<이슈> 찾기(이슈ID id);
    }

    interface 용어집저장소 {
        /** 같은 표기가 이미 있으면 무시한다 — 존재 여부를 따로 묻지 않는다. */
        void 추가(List<용어> 용어들);
        List<용어> 전량();
    }
}
