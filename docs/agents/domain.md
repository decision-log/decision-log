# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

This repo is **single-context**: one `CONTEXT.md` at the root, one `docs/adr/`.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root — the ubiquitous language glossary.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill creates them lazily when terms or decisions actually get resolved.

## File structure

```
/
├── CONTEXT.md
└── docs/
    ├── adr/
    │   └── 0001-english-identifiers-korean-vocabulary.md
    ├── mvp-scope.md
    ├── stt-requirements.md
    └── stack.md
```

| File | What it settles |
| --- | --- |
| `CONTEXT.md` | the three terms the current build actually touches |
| `docs/mvp-scope.md` | what is being built now, how "done" is judged, what is deliberately not built |
| `docs/stt-requirements.md` | measured facts about speech-to-text and the provider in use |
| `docs/stack.md` | the stack and why each piece was chosen |

The current build target is the **tracer bullet** in `docs/mvp-scope.md`. Anything outside it is deliberately deferred — check the *하지 않는 것* table there before proposing it.

## Use the glossary's vocabulary

When your output names a domain concept **in prose** (an issue title, a refactor proposal, a hypothesis, a commit message), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

`CONTEXT.md` 은 한국어 용어집이며 각 항목의 `_Avoid_` 목록에 있는 말은 쓰지 않는다.

**코드 식별자는 다르다.** 타입·함수·필드·컬럼 이름은 영문이고, 용어집 항목에 대응하는 영문 이름은 [ADR 0001](../adr/0001-english-identifiers-korean-vocabulary.md) 의 대응표가 정본이다. 용어집 어휘를 그대로 식별자로 옮기지 않는다. 테스트 이름은 예외로 한국어를 쓴다.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0001 (english identifiers) — but worth reopening because…_
