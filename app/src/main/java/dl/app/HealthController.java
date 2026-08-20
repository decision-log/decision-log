package dl.app;

import dl.domain.ports.저장소.이슈저장소;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class HealthController {
    private final 이슈저장소 이슈들;
    public HealthController(이슈저장소 이슈들) { this.이슈들 = 이슈들; }

    /** DB 를 실제로 한 번 왕복한다 — 걷는 뼈대가 서 있는지 */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "이슈", 이슈들.전량().size());
    }
}
