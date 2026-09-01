# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

This repo is **single-context**: one `CONTEXT.md` at the root, one `docs/adr/`.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — the ubiquitous language glossary.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

```
/
├── CONTEXT.md
└── docs/
    ├── adr/
    │   ├── 0001-issue-state-model.md
    │   ├── 0002-ai-cannot-decide-issues.md
    │   ├── 0003-no-external-task-tool.md
    │   ├── 0004-scope-by-substitute.md
    │   ├── 0005-simulate-rounds-not-calls.md
    │   └── 0006-english-identifiers-korean-vocabulary.md
    ├── seams.md
    ├── stt-requirements.md
    ├── mvp-scope.md
    └── stack.md
```

## This repo uses three layers

Decisions are filed by how expensive they are to reverse. When your work touches an area, read the layer it belongs to — not just the ADRs.

| Layer | What | Where |
| --- | --- | --- |
| **A. Domain** | issue model, states, permissions, vocabulary | `CONTEXT.md` + `docs/adr/` |
| **B. External contract** | STT requirements, seam interfaces | `docs/seams.md`, `docs/stt-requirements.md` |
| **C. MVP execution** | pipeline, prompts, screens, stack, retention | `docs/mvp-scope.md`, `docs/stack.md` |

The canonical definition of the layers is the *세 개의 층* section of `docs/mvp-scope.md`. A-layer changes are schema-wide and settled up front; C-layer changes are expected to be cheap and to move.

The current build target is **v0.5**, defined in ADR 0004 and the *지금 만드는 것* section of `docs/mvp-scope.md`. Anything outside it is deliberately deferred — check the *넓히는 신호* table in ADR 0004 before proposing it.

## Use the glossary's vocabulary

When your output names a domain concept **in prose** (an issue title, a refactor proposal, a hypothesis, a commit message), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

`CONTEXT.md` 은 한국어 용어집이며 각 항목의 `_Avoid_` 목록에 있는 말은 쓰지 않는다.

**코드 식별자는 다르다.** 클래스·메서드·필드·컬럼 이름은 영문이고, 용어집 항목에 대응하는 영문 이름은 [ADR 0006](../adr/0006-english-identifiers-korean-vocabulary.md) 의 대응표가 정본이다. 용어집 어휘를 그대로 식별자로 옮기지 않는다 — 그 음역이 저장소 전체를 한글 식별자로 채운 경로였다. 테스트 **메서드** 이름은 예외로 한국어를 쓴다.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders) — but worth reopening because…_
