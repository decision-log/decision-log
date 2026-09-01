package dl.app.job;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 잡 실행기가 부르는 자리.
 *
 * #3 이 이 자리를 파고 잠깐 돌다 끝나는 가짜를 꽂았고, #4 가 그 가짜를 지우고 회차오케스트레이터를
 * 부르는 {@link RoundProcessing} 를 꽂았다. 바뀐 것은 이 안쪽이지 잡 생명주기가 아니다 —
 * 잡실행기도 JooqJobs 도 부팅 쓸어담기도 그대로다.
 *
 * 도메인 타입을 쓰지 않는다. 잡 기계장치는 전부 app 에 산다(설계 합의문 "거처") —
 * 처리가 동기가 되는 순간 증발하는 어휘는 도구의 말이지 팀의 말이 아니다.
 */
public interface Processing {

    /**
     * 진행률은 처리 구현이 임의의 분수로 보고한다.
     * 단계 이름은 고정하지 않는다 — 진짜의 단계 구조는 뒤 티켓이 정한다.
     */
    interface ProgressReporter {
        void progress(int done, int total);
    }

    /** 실패는 예외로 — 예외 메시지가 그대로 실패 사유가 된다. */
    void process(UUID meeting, Path audio, ProgressReporter reporter) throws Exception;
}
