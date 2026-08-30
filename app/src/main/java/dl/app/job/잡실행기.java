package dl.app.job;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 잡을 스레드풀에서 돌린다. 폴링 루프는 없다 — 투입은 업로드·재시도 핸들러의 직접 제출뿐이다.
 *
 * 고정 크기 풀이다. 6명 · 주 1회 · 동시 잡 1개라 큐 미들웨어는 배포만 무겁게 한다(stack.md).
 * 실패의 법의학은 이 로거의 일이다 — 잡 테이블엔 사유 한 줄만 남는다.
 */
public final class 잡실행기 {
    private static final Logger 로그 = LoggerFactory.getLogger(잡실행기.class);

    private final JooqJobs 잡들;
    private final 처리 처리;
    private final ExecutorService 풀;

    public 잡실행기(JooqJobs 잡들, 처리 처리, int 일꾼수) {
        this.잡들 = 잡들;
        this.처리 = 처리;
        var 번호 = new AtomicInteger();
        this.풀 = Executors.newFixedThreadPool(일꾼수, r -> {
            var t = new Thread(r, "잡-" + 번호.incrementAndGet());
            t.setDaemon(true);   // 잡이 JVM 종료를 붙들지 않는다 — 못 거둔 행은 다음 부팅이 거둔다
            return t;
        });
    }

    /**
     * 부팅 쓸어담기. 대기중·처리중으로 남은 잡을 전부 실패로 바꾼다.
     *
     * <p><b>여기가 {@code @PostConstruct} 인 것이 배선의 핵심이다.</b> 싱글턴 초기화는 웹 서버가
     * 커넥션을 받기 전(컨텍스트 refresh 중)에 끝나므로, 쓸어담기가 요청보다 먼저 끝나는 것이
     * 배선으로 보장된다. {@code ApplicationRunner} 로 옮기면 웹 서버 기동 뒤에 돌아 이 보장이 깨진다.
     *
     * <p>구현 전 상태 모델 검사에서 나온 제약이다. 쓸어담기가 HTTP 요청 수신과 인터리빙되면
     * 길이 7짜리 반례가 있다: 업로드 → 제출 → 마킹(처리중) → <b>쓸어담기가 그 처리중 잡을 실패로
     * 오판</b> → 사람이 재시도(리셋이 뚫린다) → 제출 → 마킹. 같은 잡을 두 스레드가 동시에 처리하고,
     * 완료에 도달한 잡이 나중에 뒤집힌다. 쓸어담기가 요청 수신 전에 끝나면 반례가 없다.
     */
    @PostConstruct
    public void 부팅쓸어담기() {
        int 거둔것 = 잡들.쓸어담기();
        if (거둔것 > 0) 로그.warn("부팅 쓸어담기: 중단된 잡 {}개를 실패로 바꿨다", 거둔것);
    }

    /** 업로드·재시도 핸들러가 <b>DB 커밋 뒤에</b> 부른다 — 커밋 전이면 워커가 그 행을 못 본다. */
    public void 제출(UUID 잡, UUID 회의, Path 오디오) {
        풀.execute(() -> 돌린다(잡, 회의, 오디오));
    }

    private void 돌린다(UUID 잡, UUID 회의, Path 오디오) {
        if (!잡들.시작표시(잡)) {
            로그.debug("잡 {} 는 대기중이 아니다 — 물러난다", 잡);
            return;
        }
        try {
            처리.처리한다(회의, 오디오, (완료, 전체) -> 잡들.진행기록(잡, 완료, 전체));
            잡들.완료표시(잡);
        } catch (Exception e) {
            // 종료(shutdownNow)로 끊긴 것은 처리의 실패가 아니다. "sleep interrupted" 같은 원시 메시지를
            // 사용자에게 보이느니 행을 처리중으로 두고 다음 부팅 쓸어담기가
            // "애플리케이션 재시작으로 중단됨"으로 거두게 한다 — 강제종료와 같은 길이다.
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                로그.info("잡 {} 가 종료로 중단됐다 — 다음 부팅 쓸어담기에 맡긴다", 잡);
                return;
            }
            로그.warn("잡 {} 실패", 잡, e);
            try {
                잡들.실패표시(잡, 사유(e));
            } catch (RuntimeException 못적음) {
                // 종료 중이라 DB 가 이미 닫혔을 수 있다 — 남은 행은 다음 부팅 쓸어담기가 거둔다
                로그.warn("잡 {} 의 실패를 기록하지 못했다", 잡, 못적음);
            }
        }
    }

    /** 예외 메시지가 그대로 실패 사유가 된다. 메시지가 없으면 타입 이름이라도 남긴다. */
    private static String 사유(Exception e) {
        var 메시지 = e.getMessage();
        return 메시지 == null || 메시지.isBlank() ? e.getClass().getSimpleName() : 메시지;
    }

    @PreDestroy
    public void 멈춘다() {
        풀.shutdownNow();
    }
}
