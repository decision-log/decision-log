-- 용어집 컬럼을 영문으로 (ADR 0006 — 식별자는 영문, 도메인 어휘는 한국어)
--
-- V1 이 만든 glossary(표기, 뜻) 은 저장소에서 유일하게 한글이던 컬럼이다.
-- 값은 그대로 한국어이고 이름만 바뀐다 — 담기는 것이 바뀌지 않으므로 데이터 이동이 없다.
--
-- 뜻을 definition 이 아니라 meaning 으로 옮긴다. CONTEXT.md 가 "뜻은 회의록에서 인용하지
-- 사람이 작문하지 않는다" 고 못박았는데 definition 은 작문된 것을 가리킨다.
alter table glossary rename column 표기 to spelling;
alter table glossary rename column 뜻   to meaning;
