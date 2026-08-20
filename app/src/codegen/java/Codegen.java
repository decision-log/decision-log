import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.*;
import org.testcontainers.containers.PostgreSQLContainer;

/** 마이그레이션이 정본. 컨테이너에 적용한 뒤 거기서 코드를 뽑는다. */
public class Codegen {
    public static void main(String[] args) throws Exception {
        String out = args[0], migrations = args[1];
        long t0 = System.nanoTime();

        try (var pg = new PostgreSQLContainer<>("postgres:17-alpine")) {
            pg.start();
            long t1 = System.nanoTime();

            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                  .locations("filesystem:" + migrations).load().migrate();
            long t2 = System.nanoTime();

            GenerationTool.generate(new Configuration()
                .withJdbc(new Jdbc().withDriver("org.postgresql.Driver").withUrl(pg.getJdbcUrl())
                          .withUser(pg.getUsername()).withPassword(pg.getPassword()))
                .withGenerator(new Generator()
                    .withDatabase(new Database()
                        .withName("org.jooq.meta.postgres.PostgresDatabase")
                        .withInputSchema("public")
                        .withExcludes("flyway_schema_history"))
                    .withGenerate(new Generate().withPojos(false).withDaos(false))
                    .withTarget(new Target().withPackageName("dl.app.jooq").withDirectory(out))));
            long t3 = System.nanoTime();

            System.out.printf("%n  컨테이너 기동 %4d ms%n  Flyway      %4d ms%n  jOOQ 생성    %4d ms%n  ─────────────────%n  합계        %4d ms%n",
                    (t1-t0)/1_000_000, (t2-t1)/1_000_000, (t3-t2)/1_000_000, (t3-t0)/1_000_000);
        }
    }
}
