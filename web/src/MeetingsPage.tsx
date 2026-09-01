import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  createMeeting, fetchMeeting, fetchMeetings, fetchParticipants, retryJob, uploadAudio,
  type Job,
} from './api'

/** 잡이 아직 돌고 있을 때만 다시 묻는다 — 완료·실패는 사람이 누르기 전엔 안 변한다. */
function isRunning(job: Job | null | undefined) {
  return job?.state === '대기중' || job?.state === '처리중'
}

function today() {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

/**
 * 회의 한 장 — 목록 + 생성 폼, 회의를 고르면 같은 페이지에 상세.
 * 라우터는 깔지 않는다. 화면이 늘어날 때 화면 이슈에서 정한다.
 */
export function MeetingsPage() {
  const [selected, setSelected] = useState<string | null>(null)
  const { data: meetings } = useQuery({ queryKey: ['meetings'], queryFn: fetchMeetings })

  return (
    <section className="space-y-6">
      <h1 className="text-2xl font-semibold">회의</h1>

      <CreateForm onCreated={setSelected} />

      <div className="space-y-2">
        <h2 className="text-lg font-medium">회의 목록</h2>
        {meetings?.length === 0 && <p className="text-sm text-muted-foreground">아직 회의가 없습니다.</p>}
        <ul className="space-y-1">
          {meetings?.map(meeting => (
            <li key={meeting.id}>
              <Button
                variant={selected === meeting.id ? 'default' : 'outline'}
                onClick={() => setSelected(meeting.id)}
              >
                {meeting.title} · {meeting.heldOn}
              </Button>
            </li>
          ))}
        </ul>
      </div>

      {selected && <MeetingDetail id={selected} />}
    </section>
  )
}

/** 명단을 읽어 전체 선택된 기본값으로 보여준다. 고른 이름들이 회의에 복사된다. */
function CreateForm({ onCreated }: { onCreated: (id: string) => void }) {
  const queryClient = useQueryClient()
  const { data: roster } = useQuery({ queryKey: ['participants'], queryFn: fetchParticipants })

  const [title, setTitle] = useState('')
  const [heldOn, setHeldOn] = useState(today)
  const [picked, setPicked] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { if (roster) setPicked(roster) }, [roster])   // 전체 선택이 기본값

  const create = useMutation({
    mutationFn: () => createMeeting(title.trim(), heldOn, picked),
    onSuccess: meeting => {
      setTitle('')
      queryClient.invalidateQueries({ queryKey: ['meetings'] })
      onCreated(meeting.id)
    },
    onError: (e: Error) => setError(e.message),
  })

  function toggle(name: string) {
    setPicked(picked.includes(name)
      ? picked.filter(n => n !== name)
      : [...picked, name])
  }

  return (
    <div className="space-y-3">
      <h2 className="text-lg font-medium">새 회의</h2>

      <div className="flex items-center gap-2">
        <label htmlFor="meeting-title" className="text-sm font-medium">제목</label>
        <Input id="meeting-title" value={title} onChange={e => setTitle(e.target.value)} />
        <label htmlFor="meeting-date" className="text-sm font-medium">날짜</label>
        <Input id="meeting-date" type="date" value={heldOn} onChange={e => setHeldOn(e.target.value)} />
      </div>

      {roster && roster.length > 0 && (
        <fieldset className="flex flex-wrap items-center gap-3">
          <legend className="text-sm font-medium">참가자</legend>
          {roster.map(name => (
            <label key={name} className="flex items-center gap-1 text-sm">
              <input
                type="checkbox"
                checked={picked.includes(name)}
                onChange={() => toggle(name)}
              />
              {name}
            </label>
          ))}
        </fieldset>
      )}

      <div className="flex items-center gap-2">
        <Button
          onClick={() => { setError(null); create.mutate() }}
          disabled={create.isPending || title.trim() === ''}
        >
          만들기
        </Button>
        {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      </div>
    </div>
  )
}

function MeetingDetail({ id }: { id: string }) {
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)

  const { data: meeting } = useQuery({
    queryKey: ['meeting', id],
    queryFn: () => fetchMeeting(id),
    // 폴링은 잡이 도는 동안만 2초 — 멈춘 잡을 계속 묻지 않는다
    refetchInterval: q => (isRunning(q.state.data?.job) ? 2000 : false),
  })

  /** 업로드·재시도 직후엔 폴링 간격을 기다리지 않고 즉시 한 번 더 묻는다 */
  const refetchNow = () => queryClient.invalidateQueries({ queryKey: ['meeting', id] })

  const upload = useMutation({
    mutationFn: (file: File) => uploadAudio(id, file),
    onSuccess: refetchNow,
    onError: (e: Error) => setError(e.message),
  })

  const retry = useMutation({
    mutationFn: () => retryJob(id),
    onSuccess: refetchNow,
    onError: (e: Error) => setError(e.message),
  })

  if (!meeting) return null
  const job = meeting.job

  return (
    <div className="space-y-3 rounded-lg border p-4">
      <h2 className="text-lg font-medium">{meeting.title} · {meeting.heldOn}</h2>
      <p className="text-sm text-muted-foreground">{meeting.participants.join(', ')}</p>

      {!meeting.audioUploaded ? (
        <div className="space-y-1">
          <label htmlFor="audio" className="text-sm font-medium">오디오 파일</label>
          <Input
            id="audio"
            type="file"
            accept="audio/*"
            disabled={upload.isPending}
            onChange={e => {
              const file = e.target.files?.[0]
              // 파일을 고르는 것이 곧 처리 시작이다 — 따로 누를 버튼이 없다
              if (file) { setError(null); upload.mutate(file) }
            }}
          />
        </div>
      ) : (
        <div className="flex items-center gap-3">
          <span className="text-sm font-medium">
            {job?.state === '처리중' ? `처리중 ${job.progressDone}/${job.progressTotal}` : job?.state}
          </span>
          {job?.state === '실패' && (
            <Button onClick={() => { setError(null); retry.mutate() }} disabled={retry.isPending}>
              재시도
            </Button>
          )}
        </div>
      )}

      {job?.failureReason && <p role="alert" className="text-sm text-destructive">{job.failureReason}</p>}
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    </div>
  )
}
