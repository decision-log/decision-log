# decision-log

회의 녹음 하나가 들어가면 팀이 답해야 했던 질문(**이슈 후보**)과 그 답이 목록으로 뜬다.

무엇을 만드는지는 [docs/mvp-scope.md](./docs/mvp-scope.md), 말은 [CONTEXT.md](./CONTEXT.md), 스택은 [docs/stack.md](./docs/stack.md) 에 있다.

## 필요한 것

| | |
|---|---|
| Node 26 | `mise.toml` 에 고정돼 있다 |
| ffmpeg | 25분 넘는 녹음을 자른다 |
| `OPENAI_API_KEY` | 전사 |
| Claude Code 로그인 | 추출. `claude -p` 를 부른다 |
