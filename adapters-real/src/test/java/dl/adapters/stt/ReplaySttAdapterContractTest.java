package dl.adapters.stt;

import dl.domain.ports.SttPort;

class ReplaySttAdapterContractTest extends SttContractTest {
    @Override SttPort adapter() {
        return new ReplaySttAdapter("""
            {"text":"리버스 프록시는 Caddy로 가죠",
             "segments":[{"start":0.0,"end":3.2,"text":"리버스 프록시는 Caddy로 가죠"}]}
            """);
    }
}
