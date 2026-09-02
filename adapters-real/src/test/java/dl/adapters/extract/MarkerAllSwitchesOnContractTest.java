package dl.adapters.extract;

import dl.adapters.extract.MarkerExtractAdapter.FailureMode;
import dl.domain.ports.ExtractPort;

import java.util.EnumSet;

/**
 * 실패 모드는 계약 위반이 아니다 — 스위치 넷을 다 켜도 여섯이 그대로 참이어야 한다.
 *
 * <p>여기서 재는 것 둘: 분할이 만든 파생 키가 계약 아래서도 유일하고(②),
 * 누락으로 이슈가 빠져도 그 이슈를 참조하던 의견이 무소속이 되어 참조가 안 끊긴다(①).
 */
class MarkerAllSwitchesOnContractTest extends ExtractContractTest {
    @Override ExtractPort adapter() {
        return new MarkerExtractAdapter(EnumSet.allOf(FailureMode.class));
    }
}
