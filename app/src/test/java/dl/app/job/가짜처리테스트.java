package dl.app.job;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가짜 처리는 잡 생명주기를 시연하는 자리채움이다 — 스프링 없이 돈다.
 *
 * 단계 간격을 0으로 두면 진행 보고의 모양만 남는다.
 */
class 가짜처리테스트 {

    /** 보고된 (완료, 전체) 쌍을 순서대로 모은다 */
    static final class 보고수집 implements 처리.진행보고 {
        final List<String> 본것 = new ArrayList<>();
        @Override public void 진행(int 완료, int 전체) { 본것.add(완료 + "/" + 전체); }
    }

    @Test
    void 정상_파일은_다섯_단계를_보고하고_끝난다() throws Exception {
        var 보고 = new 보고수집();

        new 가짜처리(0).처리한다(UUID.randomUUID(), Path.of("회의.mp3"), 보고);

        assertThat(보고.본것).containsExactly("1/5", "2/5", "3/5", "4/5", "5/5");
    }

    @Test
    void 파일명에_fail_이_있으면_항상_실패한다() {
        var 보고 = new 보고수집();

        assertThatThrownBy(() -> new 가짜처리(0).처리한다(UUID.randomUUID(), Path.of("fail.mp3"), 보고))
                .hasMessageContaining("가짜")
                .hasMessageContaining("fail");

        // 진행을 보여주다 실패한다 — 화면이 진행률과 실패를 둘 다 밟는다
        assertThat(보고.본것).containsExactly("1/5", "2/5");
    }

    @Test
    void 대소문자를_가리지_않는다() {
        assertThatThrownBy(() -> new 가짜처리(0).처리한다(UUID.randomUUID(), Path.of("FAIL-회의.mp3"), new 보고수집()))
                .hasMessageContaining("가짜");
    }
}
