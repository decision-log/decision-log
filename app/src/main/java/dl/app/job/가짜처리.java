package dl.app.job;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * 진짜 전사·추출이 붙기 전까지 자리를 지키는 가짜.
 *
 * 다섯 단계를 돌며 단계마다 진행을 보고하고 완료로 끝난다.
 * 파일명에 {@code fail} 이 들어가면 항상 실패한다 — 결정적이라 테스트와 시연이 쉽다.
 * fail-once 같은 상태 있는 가짜는 만들지 않는다. "재시도가 완료로 끝나는" 시연은
 * 강제종료 시나리오(부팅 쓸어담기 → 재시도)가 맡는다.
 */
public final class 가짜처리 implements 처리 {

    private static final int 단계수 = 5;

    /** 실패임이 가짜에서 왔다고 드러나야 한다 — 진짜 실패로 오해되면 디버깅이 새는 자리다 */
    static final String 실패사유 = "가짜 처리 실패 — 파일명에 'fail'이 있어 항상 실패한다";

    /** 실패는 두 단계를 보고한 뒤에 낸다 — 화면이 진행률과 실패를 한 번에 밟는다 */
    private static final int 실패단계 = 2;

    private final long 단계간격밀리초;

    public 가짜처리(long 단계간격밀리초) { this.단계간격밀리초 = 단계간격밀리초; }

    @Override
    public void 처리한다(UUID 회의, Path 오디오, 진행보고 보고) throws Exception {
        var 파일명 = 오디오.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean 실패할것 = 파일명.contains("fail");

        for (int 단계 = 1; 단계 <= 단계수; 단계++) {
            if (단계간격밀리초 > 0) Thread.sleep(단계간격밀리초);
            보고.진행(단계, 단계수);
            if (실패할것 && 단계 == 실패단계) throw new IllegalStateException(실패사유);
        }
    }
}
