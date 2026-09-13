# 스택

```
Node 26 · TypeScript (tsc --noEmit strict) · Hono · JSX 서버 렌더링
better-sqlite3 · Zod · openai SDK · ffmpeg (child_process)
추출: claude -p (Claude Code headless, JSON 스키마 강제)
테스트: node:test
```

프로세스 하나, 설정 파일 둘(`package.json`, `tsconfig.json`), 빌드 단계 없음. Node 26 이 `.ts` 를 그대로 실행한다.

## 왜 이 스택인가

**이 프로젝트의 실패 모드는 기술적 어려움이 아니라 "안 만들어지는 것"이다.** 그래서 움직이는 부품이 가장 적은 구성을 고른다. 팀이 아는 스택인지는 저울에 올리지 않았다.

**타입은 정적 언어 수준으로 강제한다.** 이 앱에서 타입이 안 닿기 쉬운 가장 넓은 표면이 HTML 템플릿인데, Hono 의 JSX 는 그냥 TSX 라 props 오타와 누락 필드가 컴파일 에러다. LLM 이 돌려준 JSON 은 Zod 로 경계에서 잡는다.

## 개별 결정

| | | 왜 |
|---|---|---|
| Node 26 | 24 가 아니라 | 24 는 2026년 10월에 유지보수 단계로 넘어간다 |
| better-sqlite3 | 내장 `node:sqlite` 가 아니라 | 내장 sqlite 는 아직 릴리스 후보 등급이다. 안정되면 옮긴다. 그래서 DB 접근은 파일 하나에 얇게 모아 둔다 |
| JSX 서버 렌더링 | React SPA 가 아니라 | 화면 셋뿐이고 클릭이 거의 없다. 프론트 빌드가 사라진다 |
| 동기 처리 | 잡 큐가 아니라 | 올리면 끝날 때까지 기다린다. 대기 화면은 필요해지면 |
| ffmpeg 직접 호출 | 래퍼 없이 | 25분 단위로 자르는 명령 한 줄이다. Node 쪽 대표 래퍼는 deprecated 됐다 |
| `claude -p` subprocess | API SDK 가 아니라 | Claude 구독으로 돈다. `--output-format json --json-schema` 를 같이 줘야 구조화 결과가 채워진다. stdin 상한 10MB, 회의록 한 시간은 200KB 안팎 |
| OpenAI SDK | 전사만 | 전사는 구독으로 갈 길이 없어 API 키를 쓴다 |
| 오디오·회의록 | 파일시스템, 경로만 DB | 같은 녹음을 다시 돌릴 수 있어야 한다 |
| 프론트 서빙 | 같은 프로세스 | 서버 하나, CORS 없음 |

## 이 스택이 묶이는 것

`claude -p` 는 이 컴퓨터에 Claude Code 가 깔려 있고 로그인돼 있어야 돈다. 팀 서버로 옮기면 그 자리가 깨진다. 그때 API SDK 로 바꾸는 값은 추출 함수 하나다.

## 뒤집히는 경우

- 로컬 whisper 같은 ML 처리가 API 호출을 대신하게 될 때. 그때는 Python 이다
- 화면이 클릭이 많은 앱으로 자랄 때. 백엔드를 다시 쓰는 것이 아니라 앞에 한 겹 붙인다
