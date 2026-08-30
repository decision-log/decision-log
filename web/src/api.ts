/** 서버와의 유일한 창구. 컴포넌트는 fetch 를 직접 부르지 않는다. */

export type Term = { 표기: string; 뜻: string | null }
export type PasteResult = { added: number; ignored: number }

/** 표기 충돌(409)은 화면이 따로 안내해야 해서 구분되는 타입으로 던진다. */
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

export async function fetchGlossary(): Promise<Term[]> {
  return (await ok(await fetch('/api/glossary'))).json()
}

export async function pasteGlossary(text: string): Promise<PasteResult> {
  return (await ok(await fetch('/api/glossary/paste', {
    method: 'POST', headers: JSON_HEADERS, body: JSON.stringify({ text }),
  }))).json()
}

export async function updateTerm(기존표기: string, 새표기: string, 새뜻: string | null): Promise<Term> {
  return (await ok(await fetch('/api/glossary/entry', {
    method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify({ 기존표기, 새표기, 새뜻 }),
  }))).json()
}
