package dl.app;

import dl.domain.ports.단위작업;
import dl.domain.ports.저장소.명단저장소;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 참가자 명단.
 *
 * 저장은 목록 통째 교체 하나뿐이다 — 추가·삭제·이름 고치기가 전부 여기로 수렴한다.
 * 다듬기(trim)와 빈 항목 걸러내기는 여기서 하고, 중복만 거부한다.
 */
@RestController
@RequestMapping("/api/participants")
public class ParticipantController {
    private final 명단저장소 명단들;
    private final 단위작업 단위;

    public ParticipantController(명단저장소 명단들, 단위작업 단위) {
        this.명단들 = 명단들;
        this.단위 = 단위;
    }

    @GetMapping
    public List<String> 명단() { return 명단들.명단(); }

    @PutMapping
    public ResponseEntity<?> 저장(@RequestBody List<String> 이름들) {
        var 정리된것 = new ArrayList<String>();
        for (var 이름 : 이름들) {
            var 다듬은것 = 이름 == null ? "" : 이름.trim();
            if (!다듬은것.isEmpty()) 정리된것.add(다듬은것);
        }
        var 중복 = 중복찾기(정리된것);
        if (중복 != null) return ResponseEntity.badRequest().body(Map.of("error", "중복된 이름: " + 중복));

        단위.안에서(() -> 명단들.명단저장(정리된것));
        return ResponseEntity.ok(명단들.명단());
    }

    private static String 중복찾기(List<String> 이름들) {
        var 본것 = new LinkedHashSet<String>();
        for (var 이름 : 이름들) if (!본것.add(이름)) return 이름;
        return null;
    }
}
