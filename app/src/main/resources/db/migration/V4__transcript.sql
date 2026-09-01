-- 회의 하나 = 회의록 여러 개 (이슈 #4, seams.md ⓷)
--
-- V1 은 utterance 의 기본키가 (meeting_id, seq) 라 회의당 회의록이 하나였다.
-- 여러 개인 이유는 벌(vintage)이 아니라 **덩이**다 — 대기가 길면 사람이 논의를 두 덩이로 끊는다.
-- 여러 벌을 나란히 두고 판정하는 것은 추출 결과 쪽 이야기이고 재처리 티켓의 몫이다.
--
-- v0.5 화면은 여전히 1:1 로만 쓴다. 덩이를 나누는 버튼은 없다.
create table transcript (
    id         uuid primary key,
    meeting_id uuid not null references meeting(id),
    seq        int  not null,          -- 회의 안에서 몇 번째 덩이인가. 합칠 때 이 순서다
    created_at timestamptz not null default now(),
    unique (meeting_id, seq)
);

-- 기존 발화는 회의당 한 벌이었으므로 회의마다 0번 회의록을 만들어 옮긴다.
insert into transcript (id, meeting_id, seq)
select gen_random_uuid(), meeting_id, 0 from utterance group by meeting_id;

alter table utterance add column transcript_id uuid references transcript(id);

update utterance u
   set transcript_id = t.id
  from transcript t
 where t.meeting_id = u.meeting_id and t.seq = 0;

alter table utterance
    drop constraint utterance_pkey,
    drop column meeting_id,
    alter column transcript_id set not null,
    add primary key (transcript_id, seq);
