package dl.app;

import dl.domain.ports.Stores.IssueStore;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class HealthController {
    private final IssueStore issues;
    public HealthController(IssueStore issues) { this.issues = issues; }

    /** DB 를 실제로 한 번 왕복한다 — 걷는 뼈대가 서 있는지 */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "이슈", issues.all().size());
    }
}
