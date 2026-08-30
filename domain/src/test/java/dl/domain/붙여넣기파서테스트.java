package dl.domain;

import dl.domain.model.Model.용어;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 붙여넣기 파서 — 한 줄에 항목 하나, 첫 탭 또는 첫 콜론이 표기/뜻 경계.
 *
 * 결정적 변환이라 테이블로 늘어놓는다. 각 줄이 곧 형식의 명세다.
 */
class 붙여넣기파서테스트 {

    static Stream<Arguments> 표() {
        return Stream.of(
                사례("탭이 표기와 뜻을 가른다",
                        "캐디\t리버스 프록시",
                        List.of(new 용어("캐디", "리버스 프록시"))),
                사례("콜론도 가른다",
                        "캐디: 리버스 프록시",
                        List.of(new 용어("캐디", "리버스 프록시"))),
                사례("구분자가 없으면 표기만 등록한다",
                        "툴 콜링",
                        List.of(new 용어("툴 콜링", null))),
                사례("탭이 콜론보다 먼저면 탭이 경계다",
                        "캐디\t리버스 프록시: 무엇",
                        List.of(new 용어("캐디", "리버스 프록시: 무엇"))),
                사례("콜론이 탭보다 먼저면 콜론이 경계다",
                        "캐디: 리버스\t프록시",
                        List.of(new 용어("캐디", "리버스\t프록시"))),
                사례("콜론이 여럿이면 첫 콜론만 경계다",
                        "문서: https://example.com 참고",
                        List.of(new 용어("문서", "https://example.com 참고"))),
                사례("구분자 뒤가 비면 뜻은 없다",
                        "스크럼:",
                        List.of(new 용어("스크럼", null))),
                사례("표기가 비면 그 줄을 버린다",
                        ": 뜻만 있는 줄",
                        List.of()),
                사례("빈 줄과 공백 줄은 건너뛴다",
                        "가\n\n  \t \n나",
                        List.of(new 용어("가", null), new 용어("나", null))),
                사례("같은 표기는 먼저 나온 줄이 이긴다",
                        "캐디: 먼저\n캐디: 나중",
                        List.of(new 용어("캐디", "먼저"))),
                사례("표기와 뜻은 각각 다듬는다",
                        "  캐디  :   리버스 프록시  ",
                        List.of(new 용어("캐디", "리버스 프록시"))),
                사례("윈도우 줄바꿈도 줄바꿈이다",
                        "가\r\n나\r\n다",
                        List.of(new 용어("가", null), new 용어("나", null), new 용어("다", null))),
                사례("빈 텍스트는 빈 목록이다", "", List.of()));
    }

    private static Arguments 사례(String 이름, String 입력, List<용어> 기대) {
        return Arguments.of(Named.of(이름, 입력), 기대);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("표")
    void 파싱한다(String 입력, List<용어> 기대) {
        assertThat(붙여넣기파서.파싱(입력)).containsExactlyElementsOf(기대);
    }
}
