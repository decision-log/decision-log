import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { App } from './App'
import type { Meeting, MeetingDetail, Term } from './api'

let glossary: Term[]
let pastedText: string | null
let meetings: Meeting[]
let detail: MeetingDetail
let 업로드요청수: number

/** 목은 네트워크에 건다 — 화면은 테스트를 위해 모양을 바꾸지 않는다 (stack.md) */
const server = setupServer(
  http.get('*/api/participants', () => HttpResponse.json([])),
  http.get('*/api/glossary', () => HttpResponse.json(glossary)),
  http.post('*/api/glossary/paste', async ({ request }) => {
    const body = (await request.json()) as { text: string }
    pastedText = body.text
    glossary = [...glossary, { 표기: '툴 콜링', 뜻: null }, { 표기: '스크럼', 뜻: '매일 아침 회의' }]
    return HttpResponse.json({ added: 2, ignored: 1 })   // 캐디는 이미 있어 무시됐다
  }),

  http.get('*/api/meetings', () => HttpResponse.json(meetings)),
  http.get('*/api/meetings/:id', () => HttpResponse.json(detail)),
  // jsdom 의 fetch 는 FormData 본문을 다루지 못해(문자열로 뭉갠다) 여기선 파일 내용을 볼 수 없다.
  // multipart 로 파일이 실제로 건너가는지는 서버쪽 잡통합테스트가 진짜 HTTP 로 본다.
  http.post('*/api/meetings/:id/audio', () => {
    업로드요청수 += 1
    // 실패 사유는 서버가 보낸 문장을 그대로 보여준다 — 화면은 사유를 해석하지 않는다
    detail = {
      ...detail,
      audioUploaded: true,
      job: {
        state: '실패',
        progressDone: 0,
        progressTotal: 1,
        failureReason: "시뮬레이터 STT 는 대본(UTF-8 텍스트)을 받는다 — '회의녹음.mp3' 은 텍스트가 아니다",
      },
    }
    return HttpResponse.json(detail)
  }),
  http.post('*/api/meetings/:id/retry', () => {
    detail = { ...detail, job: { state: '대기중', progressDone: 0, progressTotal: 0, failureReason: null } }
    return HttpResponse.json(detail)
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

beforeEach(() => {
  glossary = [{ 표기: '캐디', 뜻: '리버스 프록시' }]
  pastedText = null
  meetings = []
  업로드요청수 = 0
  detail = {
    id: 'm1', title: '3월 2일 회의', heldOn: '2026-03-02',
    participants: ['가영', '나영'], audioUploaded: false, job: null,
  }
})

test('붙여넣은 용어가 용어집에 들어가고 결과가 요약된다', async () => {
  const user = userEvent.setup()
  render(<App />)

  expect(await screen.findByText('캐디')).toBeInTheDocument()

  const box = screen.getByLabelText(/한 줄에 하나/)
  await user.click(box)
  await user.paste('툴 콜링\n스크럼: 매일 아침 회의\n캐디: 리버스 프록시')
  await user.click(screen.getByRole('button', { name: '넣기' }))

  expect(await screen.findByText('2개 추가, 1개 무시(이미 있음)')).toBeInTheDocument()
  expect(await screen.findByText('스크럼')).toBeInTheDocument()
  expect(screen.getByText('툴 콜링')).toBeInTheDocument()

  expect(pastedText).toBe('툴 콜링\n스크럼: 매일 아침 회의\n캐디: 리버스 프록시')
  expect(box).toHaveValue('')
})

test('오디오를 올리면 상태가 보이고, 실패하면 다시 돌릴 수 있다', async () => {
  const user = userEvent.setup()
  meetings = [{ id: 'm1', title: '3월 2일 회의', heldOn: '2026-03-02' }]
  render(<App />)

  // 회의를 고르면 같은 페이지에 상세가 열린다 — 라우터는 없다
  await user.click(await screen.findByRole('button', { name: /3월 2일 회의/ }))
  expect(await screen.findByText('가영, 나영')).toBeInTheDocument()

  // 파일을 고르는 것이 곧 처리 시작이다 — 따로 누를 버튼이 없다
  await user.upload(
    screen.getByLabelText('오디오 파일'),
    new File(['소리'], '회의녹음.mp3', { type: 'audio/mpeg' }),
  )
  expect(업로드요청수).toBe(1)

  // 업로드 직후 즉시 한 번 더 조회한다 — 폴링 간격을 기다리지 않는다
  expect(await screen.findByText('실패')).toBeInTheDocument()
  expect(await screen.findByText(/텍스트가 아니다/)).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '재시도' }))

  expect(await screen.findByText('대기중')).toBeInTheDocument()
  expect(screen.queryByText(/텍스트가 아니다/)).not.toBeInTheDocument()
})
