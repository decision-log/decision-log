package dl.adapters.stt;

import com.sun.net.httpserver.HttpServer;
import dl.domain.ports.SttPort;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** 진짜 어댑터를 JDK 내장 HttpServer로 계약 테스트한다. 프레임워크 0. */
class OpenAiSttAdapterContractTest extends SttContractTest {

    static HttpServer server;
    static String baseUrl;

    @BeforeAll
    static void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = """
                {"text":"리버스 프록시는 Caddy로 가죠",
                 "segments":[{"start":0.0,"end":3.2,"text":"리버스 프록시는 Caddy로 가죠"}]}
                """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll static void down() { server.stop(0); }

    @Override SttPort adapter() { return new OpenAiSttAdapter(baseUrl, "test-key"); }
}
