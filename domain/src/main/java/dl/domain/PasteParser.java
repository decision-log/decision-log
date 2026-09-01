package dl.domain;

import dl.domain.model.Model.Term;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 붙여넣은 텍스트를 용어 목록으로.
 *
 * 한 줄에 항목 하나. 줄 안의 **첫 탭 또는 첫 콜론** 중 먼저 나오는 것이 표기/뜻 경계다 —
 * 스프레드시트 복사(탭)와 손 타이핑(`표기: 뜻`)을 둘 다 받는다. 구분자가 없으면 표기만 등록한다.
 *
 * 저장소로 나가기 전에 줄 안의 중복을 여기서 접는다 — 먼저 나온 줄이 이긴다.
 */
public final class PasteParser {

    private PasteParser() {}

    public static List<Term> parse(String text) {
        if (text == null) return List.of();

        var collected = new LinkedHashMap<String, Term>();
        for (var line : text.split("\\R")) {
            var entry = oneLine(line);
            if (entry != null) collected.putIfAbsent(entry.spelling(), entry);
        }
        return new ArrayList<>(collected.values());
    }

    /** 표기가 비면 버릴 줄이라는 뜻으로 null */
    private static Term oneLine(String line) {
        int tab = line.indexOf('\t'), colon = line.indexOf(':');
        int boundary = (tab < 0) ? colon : (colon < 0) ? tab : Math.min(tab, colon);

        var spelling = (boundary < 0 ? line : line.substring(0, boundary)).trim();
        if (spelling.isEmpty()) return null;

        var meaning = boundary < 0 ? "" : line.substring(boundary + 1).trim();
        return new Term(spelling, meaning.isEmpty() ? null : meaning);
    }
}
