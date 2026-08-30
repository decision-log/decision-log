package dl.domain;

import dl.domain.model.Model.용어;

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
public final class 붙여넣기파서 {

    private 붙여넣기파서() {}

    public static List<용어> 파싱(String 텍스트) {
        if (텍스트 == null) return List.of();

        var 모은것 = new LinkedHashMap<String, 용어>();
        for (var 줄 : 텍스트.split("\\R")) {
            var 항목 = 한줄(줄);
            if (항목 != null) 모은것.putIfAbsent(항목.표기(), 항목);
        }
        return new ArrayList<>(모은것.values());
    }

    /** 표기가 비면 버릴 줄이라는 뜻으로 null */
    private static 용어 한줄(String 줄) {
        int 탭 = 줄.indexOf('\t'), 콜론 = 줄.indexOf(':');
        int 경계 = (탭 < 0) ? 콜론 : (콜론 < 0) ? 탭 : Math.min(탭, 콜론);

        var 표기 = (경계 < 0 ? 줄 : 줄.substring(0, 경계)).trim();
        if (표기.isEmpty()) return null;

        var 뜻 = 경계 < 0 ? "" : 줄.substring(경계 + 1).trim();
        return new 용어(표기, 뜻.isEmpty() ? null : 뜻);
    }
}
