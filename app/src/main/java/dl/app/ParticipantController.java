package dl.app;

import dl.domain.ports.UnitOfWork;
import dl.domain.ports.Stores.RosterStore;
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
    private final RosterStore roster;
    private final UnitOfWork unit;

    public ParticipantController(RosterStore roster, UnitOfWork unit) {
        this.roster = roster;
        this.unit = unit;
    }

    @GetMapping
    public List<String> roster() { return roster.roster(); }

    @PutMapping
    public ResponseEntity<?> store(@RequestBody List<String> names) {
        var cleaned = new ArrayList<String>();
        for (var name : names) {
            var trimmed = name == null ? "" : name.trim();
            if (!trimmed.isEmpty()) cleaned.add(trimmed);
        }
        var duplicate = findDuplicates(cleaned);
        if (duplicate != null) return ResponseEntity.badRequest().body(Map.of("error", "중복된 이름: " + duplicate));

        unit.within(() -> roster.saveRoster(cleaned));
        return ResponseEntity.ok(roster.roster());
    }

    private static String findDuplicates(List<String> names) {
        var seen = new LinkedHashSet<String>();
        for (var name : names) if (!seen.add(name)) return name;
        return null;
    }
}
