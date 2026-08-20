# decision-log

회의를 녹음하면 사람 손 없이 팀이 답해야 할 질문(**이슈**)과 그 답이 기록되고,
그 기록이 다음 회의의 인식 컨텍스트가 되어 회의록 품질을 스스로 끌어올리는 도구.

설계는 [CONTEXT.md](./CONTEXT.md)(용어집)와 [docs/](./docs)(ADR·seam·스택)에 있다.

## 필요한 것

| | |
|---|---|
| JDK 25 | `./gradlew` 가 쓴다 |
| Docker | 통합 테스트와 로컬 DB, 그리고 코드 생성이 쓴다 |

Node 는 설치하지 않아도 된다 — 빌드가 자기 것을 내려받는다.

`mise` 를 쓴다면 `mise.toml` 에 버전을 적어두면 된다 (추적하지 않는다):

```toml
[tools]
java = "25.0.2"
node = "24.19.0"
```

## 실행

```bash
./gradlew :app:bootRun
```

명령 하나로 Postgres 가 뜨고([compose.yaml](./compose.yaml)), 마이그레이션이 적용되고,
화면이 애플리케이션의 정적 자원으로 서빙된다.

- 화면 — http://localhost:8080
- API — http://localhost:8080/api/health

회의 자리처럼 애플리케이션까지 컨테이너로 띄우려면:

```bash
./gradlew :app:bootJar
docker compose --profile full up --build
```

이 경로가 있어서 **나중에 서버로 옮기는 값이 거의 0 이 된다**
([stack.md](./docs/stack.md) 의 *배포는 지금 정하지 않는다*).
코드 생성이 빌드 중 컨테이너를 띄우므로 jar 는 이미지 밖에서 만든다.

프론트만 고칠 때는 HMR 이 붙은 개발 서버를 따로 띄울 수 있다 (`/api` 는 8080 으로 프록시된다):

```bash
./gradlew :app:bootRun          # 한 창
cd web && npm run dev           # 다른 창 → http://localhost:5173
```

## 테스트

```bash
./gradlew check
```

한 번에 도는 것:

| | |
|---|---|
| 경계 검사 | `domain` · `adapters-fake` · `sim` 클래스패스에 프레임워크가 없는지 |
| 계약 테스트 | STT 어댑터 세 벌(진짜 · 재생 · 시뮬레이터)이 같은 계약을 지키는지 |
| 통합 테스트 | 컨테이너 · 마이그레이션 · 생성 코드 · 배선 · HTTP 를 한 번에 |
| 화면 테스트 | Vitest |

회차 시뮬레이터는 따로 돌린다 — 대본 여러 벌을 흘려 **회차를 몇 초에** 본다:

```bash
./gradlew :sim:run
```

## 모듈

```
domain/          순수 자바. 상태 기계, 컨텍스트 조립, 회차 오케스트레이터, 포트
adapters-real/   진짜 공급자 어댑터 (Spring 사용 가능)
adapters-fake/   오염 시뮬레이터 · 마커 추출기 · 인메모리 저장소
app/             Spring Boot. HTTP, 잡, jOOQ 저장소, 배선
sim/             회차 시뮬레이터. domain + adapters-fake, Spring 부팅 없음
web/             React + Vite
```

의존 방향은 빌드에 선언되어 있고, **`domain` · `adapters-fake` · `sim` 에 웹 프레임워크가
들어가면 빌드가 실패한다** (루트 [build.gradle](./build.gradle) 의 `boundaryCheck`).
회차 시뮬레이터가 개발 루프의 중심인데 매번 프레임워크 컨텍스트를 띄우면 그 루프가 느려진다
([ADR 0005](./docs/adr/0005-simulate-rounds-not-calls.md)).

## 스키마

`app/src/main/resources/db/migration` 의 마이그레이션이 **정본**이다.
빌드가 컨테이너에 그 마이그레이션을 적용하고 거기서 타입 안전 쿼리 코드를 생성하므로,
생성 코드는 커밋하지 않고 스키마와 어긋날 수도 없다. 컬럼 이름을 틀리면 컴파일이 실패한다.
