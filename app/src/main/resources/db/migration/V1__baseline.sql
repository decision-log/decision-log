create table meeting (
    id          uuid primary key,
    started_at  timestamptz not null default now()
);

create table utterance (
    meeting_id  uuid not null references meeting(id),
    seq         int  not null,
    speaker     text,
    start_sec   double precision not null,
    end_sec     double precision not null,
    text        text not null,
    primary key (meeting_id, seq)
);

-- 후보는 추적 대상이 아니므로 이슈와 다른 테이블 (seams.md)
create table issue_candidate (
    id          uuid primary key,
    meeting_id  uuid not null references meeting(id),
    question    text not null,
    state       text not null,
    answer      text,
    spans       int[] not null default '{}',
    promoted    boolean not null default false
);

create table issue (
    id          uuid primary key,
    question    text not null,
    state       text not null,
    answer      text
);

create table glossary (
    표기        text primary key,
    뜻          text
);
