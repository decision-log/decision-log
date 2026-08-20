import { render, screen } from '@testing-library/react'
import { App } from './App'

test('빈 화면이 뜬다', () => {
  render(<App />)
  expect(screen.getByRole('heading')).toHaveTextContent('회의 이슈 기록')
})
