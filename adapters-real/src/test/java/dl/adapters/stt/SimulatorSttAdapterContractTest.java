package dl.adapters.stt;

import dl.domain.ports.SttPort;

class SimulatorSttAdapterContractTest extends SttContractTest {
    @Override SttPort adapter() {
        return new SimulatorSttAdapter(MeasuredCorruptionRules.all(), 42L);
    }
}
