import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SettingsPage } from './SettingsPage'

export function App() {
  // 재시도를 끈다 — 실패는 화면이 바로 말해야 하고, 테스트가 기다릴 이유도 없다.
  // 포커스 refetch 도 끈다 — 명단 편집 중 창을 벗어났다 돌아오면 저장 안 한 편집이 덮인다.
  const [client] = useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  }))

  return (
    <QueryClientProvider client={client}>
      <SettingsPage />
    </QueryClientProvider>
  )
}
