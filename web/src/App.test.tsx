import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { setupServer } from 'msw/node'
import { http, HttpResponse } from 'msw'
import { App } from './App'

type Term = { 표기: string; 뜻: string | null }

let glossary: Term[]
let pastedText: string | null

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
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

beforeEach(() => {
  glossary = [{ 표기: '캐디', 뜻: '리버스 프록시' }]
  pastedText = null
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
