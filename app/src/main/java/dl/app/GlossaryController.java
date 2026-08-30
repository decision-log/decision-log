package dl.app;

import dl.domain.model.Model.용어;
import dl.domain.붙여넣기파서;
import dl.domain.ports.단위작업;
import dl.domain.ports.저장소.용어집저장소;
import dl.domain.ports.저장소.표기충돌;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 용어집 — 전량 · 붙여넣기 · 항목 수정.
 *
 * 붙여넣기는 확인(승격)을 거치지 않고 바로 들어간다. 이미 있는 표기는 저장소가 무시하므로
 * 두 번 넣어도 결과가 같고, 무시된 수는 추가 전후 개수 차로 센다.
 */
@RestController
@RequestMapping("/api/glossary")
public class GlossaryController {
    private final 용어집저장소 용어집;
    private final 단위작업 단위;

    public GlossaryController(용어집저장소 용어집, 단위작업 단위) {
        this.용어집 = 용어집;
        this.단위 = 단위;
    }

    public record 붙여넣기(String text) {}
    public record 넣은결과(int added, int ignored) {}
    public record 수정요청(String 기존표기, String 새표기, String 새뜻) {}

    @GetMapping
    public List<용어> 전량() { return 용어집.전량(); }

    @PostMapping("/paste")
    public 넣은결과 넣기(@RequestBody 붙여넣기 요청) {
        var 용어들 = 붙여넣기파서.파싱(요청.text());
        int 추가된수 = 단위.안에서(() -> {
            int 전 = 용어집.전량().size();
            용어집.추가(용어들);
            return 용어집.전량().size() - 전;
        });
        return new 넣은결과(추가된수, 용어들.size() - 추가된수);
    }

    /** 표기가 `/` 나 콜론을 품을 수 있어 경로변수가 아니라 본문으로 받는다. */
    @PutMapping("/entry")
    public ResponseEntity<?> 수정(@RequestBody 수정요청 요청) {
        var 새표기 = 요청.새표기() == null ? "" : 요청.새표기().trim();
        if (새표기.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "표기는 비울 수 없습니다"));

        var 새뜻 = 요청.새뜻() == null || 요청.새뜻().isBlank() ? null : 요청.새뜻().trim();
        단위.안에서(() -> 용어집.수정(요청.기존표기(), 새표기, 새뜻));
        return ResponseEntity.ok(new 용어(새표기, 새뜻));
    }

    @ExceptionHandler(표기충돌.class)
    public ResponseEntity<?> 충돌(표기충돌 e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "이미 있는 표기입니다"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> 없음(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "없는 표기입니다"));
    }
}
