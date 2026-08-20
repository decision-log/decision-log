package dl.adapters.stt;

import dl.domain.ports.SttPort;

import java.util.List;

class SimulatorSttAdapterContractTest extends SttContractTest {
    @Override SttPort adapter() {
        return new SimulatorSttAdapter(
                List.of("리버스 프록시는 Caddy로 가죠"),
                List.of(new SimulatorSttAdapter.Rule("Caddy", SimulatorSttAdapter.Mode.일관, List.of("캐디"))),
                42L);
    }
}
