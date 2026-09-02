package dl.adapters.extract;

import dl.domain.ports.ExtractPort;

class MarkerExtractAdapterContractTest extends ExtractContractTest {
    @Override ExtractPort adapter() {
        return new MarkerExtractAdapter();
    }
}
