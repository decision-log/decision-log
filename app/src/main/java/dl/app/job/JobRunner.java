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
public final class JobRunner {
    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JooqJobs jobs;
    private final Processing processing;
    private final ExecutorService pool;

    public JobRunner(JooqJobs jobs, Processing processing, int workers) {
        this.jobs = jobs;
        this.processing = processing;
        var counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(workers, r -> {
            var t = new Thread(r, "잡-" + counter.incrementAndGet());
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
    public void sweepOnBoot() {
        int swept = jobs.sweep();
        if (swept > 0) log.warn("부팅 쓸어담기: 중단된 잡 {}개를 실패로 바꿨다", swept);
    }

    /** 업로드·재시도 핸들러가 <b>DB 커밋 뒤에</b> 부른다 — 커밋 전이면 워커가 그 행을 못 본다. */
    public void submit(UUID job, UUID meeting, Path audio) {
        pool.execute(() -> runJob(job, meeting, audio));
    }

    private void runJob(UUID job, UUID meeting, Path audio) {
        if (!jobs.markStarted(job)) {
            log.debug("잡 {} 는 대기중이 아니다 — 물러난다", job);
            return;
        }
        try {
            processing.process(meeting, audio, (DONE, total) -> jobs.recordProgress(job, DONE, total));
            jobs.markDone(job);
        } catch (Exception e) {
            // 종료(shutdownNow)로 끊긴 것은 처리의 실패가 아니다. "sleep interrupted" 같은 원시 메시지를
            // 사용자에게 보이느니 행을 처리중으로 두고 다음 부팅 쓸어담기가
            // "애플리케이션 재시작으로 중단됨"으로 거두게 한다 — 강제종료와 같은 길이다.
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                log.info("잡 {} 가 종료로 중단됐다 — 다음 부팅 쓸어담기에 맡긴다", job);
                return;
            }
            log.warn("잡 {} 실패", job, e);
            try {
                jobs.markFailed(job, reasonOf(e));
            } catch (RuntimeException writeFailed) {
                // 종료 중이라 DB 가 이미 닫혔을 수 있다 — 남은 행은 다음 부팅 쓸어담기가 거둔다
                log.warn("잡 {} 의 실패를 기록하지 못했다", job, writeFailed);
            }
        }
    }

    /** 예외 메시지가 그대로 실패 사유가 된다. 메시지가 없으면 타입 이름이라도 남긴다. */
    private static String reasonOf(Exception e) {
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @PreDestroy
    public void stop() {
        pool.shutdownNow();
    }
}
