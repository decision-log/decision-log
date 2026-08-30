import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  createMeeting, fetchMeeting, fetchMeetings, fetchParticipants, retryJob, uploadAudio,
  type Job,
} from './api'

/** 잡이 아직 돌고 있을 때만 다시 묻는다 — 완료·실패는 사람이 누르기 전엔 안 변한다. */
function 도는중(job: Job | null | undefined) {
  return job?.state === '대기중' || job?.state === '처리중'
}

function 오늘() {
  const 지금 = new Date()
  const 두자리 = (n: number) => String(n).padStart(2, '0')
  return `${지금.getFullYear()}-${두자리(지금.getMonth() + 1)}-${두자리(지금.getDate())}`
}

/**
 * 회의 한 장 — 목록 + 생성 폼, 회의를 고르면 같은 페이지에 상세.
 * 라우터는 깔지 않는다. 화면이 늘어날 때 화면 이슈에서 정한다.
 */
export function MeetingsPage() {
  const [고른회의, 고르기] = useState<string | null>(null)
  const { data: 회의들 } = useQuery({ queryKey: ['meetings'], queryFn: fetchMeetings })

  return (
    <section className="space-y-6">
      <h1 className="text-2xl font-semibold">회의</h1>

      <생성폼 만들어졌을때={고르기} />

      <div className="space-y-2">
        <h2 className="text-lg font-medium">회의 목록</h2>
        {회의들?.length === 0 && <p className="text-sm text-muted-foreground">아직 회의가 없습니다.</p>}
        <ul className="space-y-1">
          {회의들?.map(회의 => (
            <li key={회의.id}>
              <Button
                variant={고른회의 === 회의.id ? 'default' : 'outline'}
                onClick={() => 고르기(회의.id)}
              >
                {회의.title} · {회의.heldOn}
              </Button>
            </li>
          ))}
        </ul>
      </div>

      {고른회의 && <회의상세 id={고른회의} />}
    </section>
  )
}

/** 명단을 읽어 전체 선택된 기본값으로 보여준다. 고른 이름들이 회의에 복사된다. */
function 생성폼({ 만들어졌을때 }: { 만들어졌을때: (id: string) => void }) {
  const queryClient = useQueryClient()
  const { data: 명단 } = useQuery({ queryKey: ['participants'], queryFn: fetchParticipants })

  const [제목, 제목바꾸기] = useState('')
  const [날짜, 날짜바꾸기] = useState(오늘)
  const [고른이름, 고른이름바꾸기] = useState<string[]>([])
  const [오류, 오류바꾸기] = useState<string | null>(null)

  useEffect(() => { if (명단) 고른이름바꾸기(명단) }, [명단])   // 전체 선택이 기본값

  const 만들기 = useMutation({
    mutationFn: () => createMeeting(제목.trim(), 날짜, 고른이름),
    onSuccess: 회의 => {
      제목바꾸기('')
      queryClient.invalidateQueries({ queryKey: ['meetings'] })
      만들어졌을때(회의.id)
    },
    onError: (e: Error) => 오류바꾸기(e.message),
  })

  function 토글(이름: string) {
    고른이름바꾸기(고른이름.includes(이름)
      ? 고른이름.filter(n => n !== 이름)
      : [...고른이름, 이름])
  }

  return (
    <div className="space-y-3">
      <h2 className="text-lg font-medium">새 회의</h2>

      <div className="flex items-center gap-2">
        <label htmlFor="meeting-title" className="text-sm font-medium">제목</label>
        <Input id="meeting-title" value={제목} onChange={e => 제목바꾸기(e.target.value)} />
        <label htmlFor="meeting-date" className="text-sm font-medium">날짜</label>
        <Input id="meeting-date" type="date" value={날짜} onChange={e => 날짜바꾸기(e.target.value)} />
      </div>

      {명단 && 명단.length > 0 && (
        <fieldset className="flex flex-wrap items-center gap-3">
          <legend className="text-sm font-medium">참가자</legend>
          {명단.map(이름 => (
            <label key={이름} className="flex items-center gap-1 text-sm">
              <input
                type="checkbox"
                checked={고른이름.includes(이름)}
                onChange={() => 토글(이름)}
              />
              {이름}
            </label>
          ))}
        </fieldset>
      )}

      <div className="flex items-center gap-2">
        <Button
          onClick={() => { 오류바꾸기(null); 만들기.mutate() }}
          disabled={만들기.isPending || 제목.trim() === ''}
        >
          만들기
        </Button>
        {오류 && <p role="alert" className="text-sm text-destructive">{오류}</p>}
      </div>
    </div>
  )
}

function 회의상세({ id }: { id: string }) {
  const queryClient = useQueryClient()
  const [오류, 오류바꾸기] = useState<string | null>(null)

  const { data: 회의 } = useQuery({
    queryKey: ['meeting', id],
    queryFn: () => fetchMeeting(id),
    // 폴링은 잡이 도는 동안만 2초 — 멈춘 잡을 계속 묻지 않는다
    refetchInterval: q => (도는중(q.state.data?.job) ? 2000 : false),
  })

  /** 업로드·재시도 직후엔 폴링 간격을 기다리지 않고 즉시 한 번 더 묻는다 */
  const 즉시다시읽기 = () => queryClient.invalidateQueries({ queryKey: ['meeting', id] })

  const 업로드 = useMutation({
    mutationFn: (파일: File) => uploadAudio(id, 파일),
    onSuccess: 즉시다시읽기,
    onError: (e: Error) => 오류바꾸기(e.message),
  })

  const 다시돌리기 = useMutation({
    mutationFn: () => retryJob(id),
    onSuccess: 즉시다시읽기,
    onError: (e: Error) => 오류바꾸기(e.message),
  })

  if (!회의) return null
  const 잡 = 회의.job

  return (
    <div className="space-y-3 rounded-lg border p-4">
      <h2 className="text-lg font-medium">{회의.title} · {회의.heldOn}</h2>
      <p className="text-sm text-muted-foreground">{회의.participants.join(', ')}</p>

      {!회의.audioUploaded ? (
        <div className="space-y-1">
          <label htmlFor="audio" className="text-sm font-medium">오디오 파일</label>
          <Input
            id="audio"
            type="file"
            accept="audio/*"
            disabled={업로드.isPending}
            onChange={e => {
              const 파일 = e.target.files?.[0]
              // 파일을 고르는 것이 곧 처리 시작이다 — 따로 누를 버튼이 없다
              if (파일) { 오류바꾸기(null); 업로드.mutate(파일) }
            }}
          />
        </div>
      ) : (
        <div className="flex items-center gap-3">
          <span className="text-sm font-medium">
            {잡?.state === '처리중' ? `처리중 ${잡.progressDone}/${잡.progressTotal}` : 잡?.state}
          </span>
          {잡?.state === '실패' && (
            <Button onClick={() => { 오류바꾸기(null); 다시돌리기.mutate() }} disabled={다시돌리기.isPending}>
              재시도
            </Button>
          )}
        </div>
      )}

      {잡?.failureReason && <p role="alert" className="text-sm text-destructive">{잡.failureReason}</p>}
      {오류 && <p role="alert" className="text-sm text-destructive">{오류}</p>}
    </div>
  )
}
