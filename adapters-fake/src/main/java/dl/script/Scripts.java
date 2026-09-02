package dl.script;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 마커 대본 (ADR 0005) — 정답을 미리 달아둔 합성 회의다.
 *
 * <p><b>대본은 리소스 파일 한 벌이고 읽는 자리도 하나다.</b> 회차 시뮬레이터와 통합 테스트에
 * 각각 인라인으로 박혀 두 벌로 갈라져 있던 것이 #5 가 지운 상태다 — 갈라져 있으면 한 벌만 자란다.
 */
public final class Scripts {

    private static final String DEFAULT = "dl/script/default.txt";

    /**
     * 기본 대본 전문. 파일을 읽는 자리는 여기 하나다.
     *
     * <p><b>{@code #} 로 시작하는 줄은 여기서 빠진다.</b> 마커 문법이 담을 것이 많아져 설명을
     * 파일 머리에 둬야 대본을 눈으로 읽을 수 있는데, 그 설명은 발화가 아니다.
     *
     * <p>걸러내는 자리가 <b>파일을 읽는 시점</b>인 것이 중요하다. STT 어댑터에 넣으면 사람이
     * 올린 대본에서 {@code #} 로 시작하는 진짜 발화가 조용히 사라진다 — <i>"올린 파일이 곧
     * 대본이고 줄 하나가 발화 하나"</i> 는 그 경계가 #4 에서 정한 것이라 여기서 안 바꾼다.
     */
    public static String defaultScript() {
        try (var in = Scripts.class.getClassLoader().getResourceAsStream(DEFAULT)) {
            if (in == null) throw new IllegalStateException("대본 리소스가 없다: " + DEFAULT);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.stripLeading().startsWith("#"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 대본 문자열을 발화 줄로 — 줄 하나가 발화 하나다 (ADR 0005). */
    public static List<String> lines(String script) {
        return script.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private Scripts() {}
}
