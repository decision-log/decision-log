package dl.app;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static dl.app.jooq.Tables.JOB;
import static dl.app.jooq.Tables.MEETING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "띄우면 바뀐다"를 실제로 밟는다 — 애플리케이션을 두 번 기동한다.
 *
 * 프로세스가 죽으면 그 잡을 돌던 스레드도 같이 죽는다. 남은 대기중·처리중 행은 전부 유령이라
 * 다시 뜰 때 실패로 바꾼다. 자동 재개는 없다 — 잡이 앱을 죽인 원인이면 자동 재개는 죽음의 반복이다.
 *
 * <p>쓸어담기가 {@code @PostConstruct} 에 있다는 배선 자체가 여기서 검증된다.
 * ApplicationRunner 로 옮기면 웹 서버가 뜬 뒤에 돌아 요청과 인터리빙될 수 있고,
 * 모델 검사가 그 배선에서 반례를 찾았다(잡실행기.부팅쓸어담기 javadoc).
 */
@Testcontainers
class BootSweepIntegrationTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @TempDir Path dataDir;

    @Test
    void 재시작하면_중단된_잡이_실패로_바뀐다() {
        var strandedJob = UUID.randomUUID();
        var finishedJob = UUID.randomUUID();

        try (var firstBoot = boot()) {
            var db = firstBoot.getBean(DSLContext.class);
            seedJob(db, strandedJob, "처리중", 3);
            seedJob(db, finishedJob, "완료", 5);
        }   // 강제종료와 같다 — 처리중 행이 그대로 남는다

        try (var secondBoot = boot()) {
            var db = secondBoot.getBean(DSLContext.class);

            var stranded = db.select(JOB.STATE, JOB.FAILURE_REASON)
                            .from(JOB).where(JOB.ID.eq(strandedJob)).fetchSingle();
            assertThat(stranded.get(JOB.STATE)).isEqualTo("실패");
            assertThat(stranded.get(JOB.FAILURE_REASON)).isEqualTo("애플리케이션 재시작으로 중단됨");

            // 완료에 도달한 잡은 다시 뜬다고 뒤집히지 않는다
            var finished = db.select(JOB.STATE).from(JOB).where(JOB.ID.eq(finishedJob)).fetchSingle();
            assertThat(finished.get(JOB.STATE)).isEqualTo("완료");
        }
    }

    private void seedJob(DSLContext db, UUID job, String state, int progress) {
        var meeting = UUID.randomUUID();
        db.insertInto(MEETING).columns(MEETING.ID, MEETING.TITLE, MEETING.HELD_ON)
          .values(meeting, state + " 회의", LocalDate.of(2026, 3, 2)).execute();
        db.insertInto(JOB)
          .columns(JOB.ID, JOB.MEETING_ID, JOB.STATE, JOB.PROGRESS_DONE, JOB.PROGRESS_TOTAL)
          .values(job, meeting, state, progress, 5).execute();
    }

    /** 프로덕션과 같은 배선으로 띄운다 — 손으로 빈을 만들면 검증하려는 배선이 사라진다. */
    private ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(App.class)
                .properties("server.port=0",
                            "spring.datasource.url=" + pg.getJdbcUrl(),
                            "spring.datasource.username=" + pg.getUsername(),
                            "spring.datasource.password=" + pg.getPassword(),
                            "dl.data-dir=" + dataDir)
                .run();
    }
}
