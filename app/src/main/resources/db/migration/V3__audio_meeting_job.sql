-- 오디오 업로드 · 회의 참가자 스냅샷 · 잡 (이슈 #3)
--
-- participant 테이블은 V2 가 이미 만들었다 (#6 이 먼저 들어왔다) — 여기선 안 만든다.
--
-- title · held_on 에 기본값을 둔 이유: 회차오케스트레이터의 돈다() 가 아직 회의를 자기가
-- 만든다(회의저장소.새회의() 는 id 만 넣는다). 그 충돌 해소는 진짜 연결 티켓의 몫으로
-- 미뤄져 있으므로(설계 합의문 "미룬 충돌"), not null 을 지키면서 기존 경로를 안 깨는
-- 방법으로 기본값을 쓴다. 사람이 만드는 회의는 API 가 항상 값을 채운다.
alter table meeting
    add column title      text not null default '',
    add column held_on    date not null default current_date,   -- 사용자가 고르는 회의 날짜
    add column audio_path text;                                 -- 회의당 오디오 하나

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
