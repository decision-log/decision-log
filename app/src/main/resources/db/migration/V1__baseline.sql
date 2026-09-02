-- 동결선: 첫 확인. 팀이 회의에서 후보를 이슈로 올리는 순간부터 되살릴 수 없는 데이터가 생긴다.
-- 그 전에는 이 파일을 고치고, 그 뒤로는 V2 부터 더한다.
--
-- 동결선 전에는 마이그레이션이 아니라 스키마 정의다 — 그래서 baseline 이 이름값을 한다.
-- 대가는 둘이다: 개발 DB 와 compose 볼륨을 한 번 날려야 하고, "무엇이 언제 왜 추가됐나" 가
-- 파일 경계 대신 커밋 로그와 아래 주석에만 남는다. 이관한 설계 기록은 그 자리마다 붙어 있다.

create table meeting (
    id          uuid primary key,
    started_at  timestamptz not null default now(),

    -- title · held_on 에 기본값을 두는 이유: MeetingStore.newMeeting() 이 id 만 넣는다.
    -- 사람 없이 회차를 도는 쪽(회차 시뮬레이터)에 남은 길이라 채울 제목도 날짜도 없다.
    -- 기본값이 없으면 not null 이 그 길을 막는다. 사람이 만드는 회의는 API 가 항상 값을 채운다.
    --
    -- (#3 이 "회차오케스트레이터가 회의를 자기가 만든다" 를 이유로 적었던 자리인데, 그 충돌은
    --  #4 가 해소했다 — RoundOrchestrator.run 은 회의ID 를 받고 회의를 만들지 않는다.)
    title       text not null default '',
    held_on     date not null default current_date,   -- 사용자가 고르는 회의 날짜
    audio_path  text,                                 -- 회의당 오디오 하나

    -- 현재 벌. 재처리는 이전 벌을 지우지 않고 이 포인터를 옮긴다 — 지우면 나란히 볼 대상이
    -- 사라져 "세 벌을 돌려보고 두 번째가 제일 낫다" 는 판정이 불가능해진다.
    -- FK 는 extraction 을 만든 뒤 아래에서 건다 (상호 참조).
    current_extraction_id uuid
);

create table participant (
    name text primary key
);

-- 스냅샷 복사 — participant 쪽으로 FK 를 걸지 않는다.
-- 명단은 통째 교체·이름 고치기가 자유롭고(#6 합의), 그 합의의 전제가 "명단을 참조하는 게 없다"였다.
-- 명단이 나중에 바뀌어도 그 회의에 그 사람들이 있었다는 사실은 안 변한다.
create table meeting_participant (
    meeting_id uuid not null references meeting(id),
    name       text not null,
    primary key (meeting_id, name)
);

-- 오디오 업로드 하나 = 잡 하나. 회의당 잡 유니크.
create table job (
    id             uuid primary key,
    meeting_id     uuid not null unique references meeting(id),
    state          text not null,        -- 대기중 · 처리중 · 완료 · 실패
    progress_done  int  not null default 0,
    progress_total int  not null default 0,
    failure_reason text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

-- 회의 하나 = 회의록 여러 개 (seams.md ⓷)
--
-- 여러 개인 이유는 벌(vintage)이 아니라 **덩이**다 — 대기가 길면 사람이 논의를 두 덩이로 끊는다.
-- 여러 벌을 나란히 두고 판정하는 것은 추출 결과 쪽 이야기이고 아래 extraction 이 그 자리다.
--
-- v0.5 화면은 여전히 1:1 로만 쓴다. 덩이를 나누는 버튼은 없다.
create table transcript (
    id         uuid primary key,
    meeting_id uuid not null references meeting(id),
    seq        int  not null,          -- 회의 안에서 몇 번째 덩이인가. 합칠 때 이 순서다
    created_at timestamptz not null default now(),
    unique (meeting_id, seq)
);

create table utterance (
    transcript_id uuid not null references transcript(id),
    seq           int  not null,
    speaker       text,
    start_sec     double precision not null,
    end_sec       double precision not null,
    text          text not null,
    primary key (transcript_id, seq)
);

create table issue (
    id          uuid primary key,
    question    text not null,
    state       text not null,
    answer      text
);

-- 값은 한국어이고 이름만 영문이다 (ADR 0006 — 식별자는 영문, 도메인 어휘는 한국어).
-- 뜻을 definition 이 아니라 meaning 으로 두는 이유: CONTEXT.md 가 "뜻은 회의록에서 인용하지
-- 사람이 작문하지 않는다" 고 못박았는데 definition 은 작문된 것을 가리킨다.
create table glossary (
    spelling    text primary key,
    meaning     text
);

-- 벌 하나. 한 회의에 여러 벌이 병존한다 — 추출 품질이 이 설계 전체가 걸린 자리라
-- 같은 회의록에 여러 (프롬프트, 모델) 조합을 돌려 나란히 봐야 관측된다 (seams.md ⓶).
create table extraction (
    id               uuid primary key,
    meeting_id       uuid not null references meeting(id),
    model_name       text not null,
    prompt_version   text not null,
    prompt_hash      text not null,

    -- 토큰수는 통째로 optional 이다. 0 을 채우면 "측정되지 않았다" 가 "0 이었다" 로 읽혀
    -- 여러 벌을 나란히 놓을 때 그 벌이 제일 싼 것으로 찍힌다. 그래서 셋이 함께 있거나 함께 없다.
    tokens_input     bigint,
    tokens_output    bigint,
    tokens_cache_hit bigint,

    created_at       timestamptz not null,
    check ((tokens_input is null and tokens_output is null and tokens_cache_hit is null)
        or (tokens_input is not null and tokens_output is not null and tokens_cache_hit is not null))
);

alter table meeting
    add constraint meeting_current_extraction_fk
    foreign key (current_extraction_id) references extraction(id);

-- 후보는 추적 대상이 아니므로 이슈와 다른 테이블 (CONTEXT.md · seams.md)
--
-- meeting_id 를 안 갖는다 — extraction 경유 조인 하나가 두 경로가 어긋나는 것보다 싸다.
-- promoted 불리언 대신 promoted_issue_id 를 둔다: 확인됐는지(null 여부)와 어느 이슈가 됐는지를
-- 한 컬럼이 답하고, #17 의 "이번에 뽑힌 후보를 기존 이슈에 연결한다" 가 스키마 변경 없이 된다.
create table issue_candidate (
    id                uuid primary key,
    extraction_id     uuid not null references extraction(id),
    question          text not null,
    state             text not null,        -- 쟁점 · 결정 (추출은 무효 · 실행됨을 제안하지 않는다)
    answer            text,
    undecided_reason  text,
    promoted_issue_id uuid references issue(id)
);

create table term_candidate (
    id            uuid primary key,
    extraction_id uuid not null references extraction(id),
    spelling      text not null,
    meaning       text
);

-- 근거는 테이블 둘 + 인라인 둘이다. 소유자가 넷이 아니라 둘인 것은 의견·할 일의 근거가
-- 표면상 단수이기 때문이다 (seams.md ⓶) — 단수면 "근거 비지 않음" 이 not null 하나로 강제된다.
--
-- (transcript_id, seq) 가 마침 utterance 의 기본키다. 근거를 거기에 FK 로 걸면 계약 ③ 구간범위를
-- DB 가 함께 지킨다 — 없는 발화를 가리키는 근거가 원리적으로 안 들어간다.
--
-- 다형 한 장(owner_kind, owner_id)을 안 쓰는 이유는 owner_id 에 FK 를 못 걸어 고아가 조용히
-- 생기고 owner_kind 오타가 컴파일에서 안 잡히기 때문이다 — 스키마가 정본이고 코드가 파생물이라
-- 스키마가 움직여도 컴파일에서 잡힌다는 stack.md 의 그 자리를 정확히 되돌린다.
create table issue_candidate_evidence (
    candidate_id  uuid not null references issue_candidate(id),
    transcript_id uuid not null,
    seq           int  not null,
    primary key (candidate_id, transcript_id, seq),
    foreign key (transcript_id, seq) references utterance(transcript_id, seq)
);

create table term_candidate_evidence (
    term_candidate_id uuid not null references term_candidate(id),
    transcript_id     uuid not null,
    seq               int  not null,
    primary key (term_candidate_id, transcript_id, seq),
    foreign key (transcript_id, seq) references utterance(transcript_id, seq)
);

-- 화면은 이번 범위에 없지만 뽑아서 저장한다 — 호출 비용이 같고 나중에 화면 붙일 때 데이터가 이미 있다.
-- issue_candidate_id 가 null 인 것은 무소속이다: 어느 이슈에도 안 붙는 의견이 실제로 나온다.
create table opinion (
    id                  uuid primary key,
    extraction_id       uuid not null references extraction(id),
    issue_candidate_id  uuid references issue_candidate(id),
    speaker_label       text,
    text                text not null,
    evidence_transcript uuid not null,
    evidence_seq        int  not null,
    foreign key (evidence_transcript, evidence_seq) references utterance(transcript_id, seq)
);

create table task (
    id                  uuid primary key,
    extraction_id       uuid not null references extraction(id),
    issue_candidate_id  uuid references issue_candidate(id),
    text                text not null,
    assignee            text,
    evidence_transcript uuid not null,
    evidence_seq        int  not null,
    foreign key (evidence_transcript, evidence_seq) references utterance(transcript_id, seq)
);
