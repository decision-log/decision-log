/** 서버와의 유일한 창구. 컴포넌트는 fetch 를 직접 부르지 않는다. */

export type Term = { spelling: string; meaning: string | null }
export type PasteResult = { added: number; ignored: number }

export type Meeting = { id: string; title: string; heldOn: string }

/** 상태는 한글 텍스트다 — 서버의 job.state 관례를 그대로 받는다. */
export type JobState = '대기중' | '처리중' | '완료' | '실패'

/** 진행률은 처리 구현이 정하는 임의의 분수다 — 단계 이름은 고정돼 있지 않다. */
export type Job = {
  state: JobState
  progressDone: number
  progressTotal: number
  failureReason: string | null
}

/** job 은 오디오를 올리기 전엔 null 이다 — 업로드 하나가 잡 하나다. */
export type MeetingDetail = Meeting & {
  participants: string[]
  audioUploaded: boolean
  job: Job | null
}

/** spelling 충돌(409)은 화면이 따로 안내해야 해서 구분되는 타입으로 던진다. */
export class ConflictError extends Error {}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

async function ok(response: Response): Promise<Response> {
  if (response.ok) return response

  let message = `요청이 실패했습니다 (${response.status})`
  try {
    const body = await response.json()
    if (body && typeof body.error === 'string') message = body.error
  } catch {
    // 본문이 JSON 이 아닐 수 있다 — 상태 코드만으로 안내한다
  }
  if (response.status === 409) throw new ConflictError(message)
  throw new Error(message)
}

export async function fetchParticipants(): Promise<string[]> {
  return (await ok(await fetch('/api/participants'))).json()
}

export async function saveParticipants(names: string[]): Promise<string[]> {
  return (await ok(await fetch('/api/participants', {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(names),
  }))).json()
}

export async function fetchMeetings(): Promise<Meeting[]> {
  return (await ok(await fetch('/api/meetings'))).json()
}

export async function fetchMeeting(id: string): Promise<MeetingDetail> {
  return (await ok(await fetch(`/api/meetings/${id}`))).json()
}

/** participants 를 안 보내면 서버가 명단 전체를 찍는다 — 여기선 화면이 고른 것을 보낸다. */
export async function createMeeting(
  title: string, heldOn: string, participants: string[],
): Promise<MeetingDetail> {
  return (await ok(await fetch('/api/meetings', {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ title, heldOn, participants }),
  }))).json()
}

/** 업로드가 곧 처리 시작이다. Content-Type 은 브라우저가 경계(boundary)와 함께 붙인다. */
export async function uploadAudio(id: string, file: File): Promise<MeetingDetail> {
  const form = new FormData()
  form.append('file', file)
  return (await ok(await fetch(`/api/meetings/${id}/audio`, { method: 'POST', body: form }))).json()
}

/** 처리중이거나 완료된 잡은 서버가 409 로 거절한다. */
export async function retryJob(id: string): Promise<MeetingDetail> {
  return (await ok(await fetch(`/api/meetings/${id}/retry`, { method: 'POST' }))).json()
}

export async function fetchGlossary(): Promise<Term[]> {
  return (await ok(await fetch('/api/glossary'))).json()
}

export async function pasteGlossary(text: string): Promise<PasteResult> {
  return (await ok(await fetch('/api/glossary/paste', {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ text }),
  }))).json()
}

export async function updateTerm(oldSpelling: string, newSpelling: string, newMeaning: string | null): Promise<Term> {
  return (await ok(await fetch('/api/glossary/entry', {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify({ oldSpelling, newSpelling, newMeaning }),
  }))).json()
}
