package dl.adapters.stt;

import dl.domain.ports.SttPort;

class SimulatorSttAdapterContractTest extends SttContractTest {
    @Override SttPort adapter() {
        return new SimulatorSttAdapter(실측오염규칙.전량(), 42L);
    }
}
