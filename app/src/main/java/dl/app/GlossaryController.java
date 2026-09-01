package dl.app;

import dl.domain.model.Model.Term;
import dl.domain.PasteParser;
import dl.domain.ports.UnitOfWork;
import dl.domain.ports.Stores.GlossaryStore;
import dl.domain.ports.Stores.SpellingConflict;
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
    private final GlossaryStore glossary;
    private final UnitOfWork unit;

    public GlossaryController(GlossaryStore glossary, UnitOfWork unit) {
        this.glossary = glossary;
        this.unit = unit;
    }

    public record PasteRequest(String text) {}
    public record PasteResult(int added, int ignored) {}
    public record EditRequest(String oldSpelling, String newSpelling, String newMeaning) {}

    @GetMapping
    public List<Term> all() { return glossary.all(); }

    @PostMapping("/paste")
    public PasteResult paste(@RequestBody PasteRequest request) {
        var terms = PasteParser.parse(request.text());
        int added = unit.within(() -> {
            int before = glossary.all().size();
            glossary.add(terms);
            return glossary.all().size() - before;
        });
        return new PasteResult(added, terms.size() - added);
    }

    /** 표기가 `/` 나 콜론을 품을 수 있어 경로변수가 아니라 본문으로 받는다. */
    @PutMapping("/entry")
    public ResponseEntity<?> edit(@RequestBody EditRequest request) {
        var newSpelling = request.newSpelling() == null ? "" : request.newSpelling().trim();
        if (newSpelling.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "표기는 비울 수 없습니다"));

        var newMeaning = request.newMeaning() == null || request.newMeaning().isBlank() ? null : request.newMeaning().trim();
        unit.within(() -> glossary.edit(request.oldSpelling(), newSpelling, newMeaning));
        return ResponseEntity.ok(new Term(newSpelling, newMeaning));
    }

    @ExceptionHandler(SpellingConflict.class)
    public ResponseEntity<?> conflict(SpellingConflict e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "이미 있는 표기입니다"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "없는 표기입니다"));
    }
}
