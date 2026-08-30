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
class 부팅쓸어담기통합테스트 {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:17-alpine");

    @TempDir Path 데이터디렉토리;

    @Test
    void 재시작하면_중단된_잡이_실패로_바뀐다() {
        var 중단된잡 = UUID.randomUUID();
        var 끝난잡 = UUID.randomUUID();

        try (var 첫기동 = 띄운다()) {
            var db = 첫기동.getBean(DSLContext.class);
            잡을심는다(db, 중단된잡, "처리중", 3);
            잡을심는다(db, 끝난잡, "완료", 5);
        }   // 강제종료와 같다 — 처리중 행이 그대로 남는다

        try (var 두번째기동 = 띄운다()) {
            var db = 두번째기동.getBean(DSLContext.class);

            var 중단된것 = db.select(JOB.STATE, JOB.FAILURE_REASON)
                            .from(JOB).where(JOB.ID.eq(중단된잡)).fetchSingle();
            assertThat(중단된것.get(JOB.STATE)).isEqualTo("실패");
            assertThat(중단된것.get(JOB.FAILURE_REASON)).isEqualTo("애플리케이션 재시작으로 중단됨");

            // 완료에 도달한 잡은 다시 뜬다고 뒤집히지 않는다
            var 끝난것 = db.select(JOB.STATE).from(JOB).where(JOB.ID.eq(끝난잡)).fetchSingle();
            assertThat(끝난것.get(JOB.STATE)).isEqualTo("완료");
        }
    }

    private void 잡을심는다(DSLContext db, UUID 잡, String 상태, int 진행) {
        var 회의 = UUID.randomUUID();
        db.insertInto(MEETING).columns(MEETING.ID, MEETING.TITLE, MEETING.HELD_ON)
          .values(회의, 상태 + " 회의", LocalDate.of(2026, 3, 2)).execute();
        db.insertInto(JOB)
          .columns(JOB.ID, JOB.MEETING_ID, JOB.STATE, JOB.PROGRESS_DONE, JOB.PROGRESS_TOTAL)
          .values(잡, 회의, 상태, 진행, 5).execute();
    }

    /** 프로덕션과 같은 배선으로 띄운다 — 손으로 빈을 만들면 검증하려는 배선이 사라진다. */
    private ConfigurableApplicationContext 띄운다() {
        return new SpringApplicationBuilder(App.class)
                .properties("server.port=0",
                            "spring.datasource.url=" + pg.getJdbcUrl(),
                            "spring.datasource.username=" + pg.getUsername(),
                            "spring.datasource.password=" + pg.getPassword(),
                            "dl.data-dir=" + 데이터디렉토리)
                .run();
    }
}
