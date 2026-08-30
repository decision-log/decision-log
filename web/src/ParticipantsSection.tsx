import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { fetchParticipants, saveParticipants } from './api'

/** 추가·삭제·이름 고치기가 전부 "고친 목록을 저장" 하나로 수렴한다. */
export function ParticipantsSection() {
  const queryClient = useQueryClient()
  const { data } = useQuery({ queryKey: ['participants'], queryFn: fetchParticipants })

  const [names, setNames] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  useEffect(() => { if (data) setNames(data) }, [data])

  const save = useMutation({
    mutationFn: saveParticipants,
    onSuccess: () => {
      setSaved(true)
      queryClient.invalidateQueries({ queryKey: ['participants'] })
    },
    onError: (e: Error) => setError(e.message),
  })

  function edit(index: number, value: string) {
    setNames(names.map((name, i) => (i === index ? value : name)))
    setSaved(false)
  }

  function remove(index: number) {
    setNames(names.filter((_, i) => i !== index))
    setSaved(false)
  }

  function submit() {
    setError(null)
    setSaved(false)

    const cleaned = names.map(n => n.trim()).filter(n => n !== '')
    const duplicate = cleaned.find((n, i) => cleaned.indexOf(n) !== i)
    if (duplicate) {                       // 중복만 막는다 — 요청은 보내지 않는다
      setError(`중복된 이름: ${duplicate}`)
      return
    }
    save.mutate(cleaned)
  }

  return (
    <section className="space-y-3">
      <h2 className="text-lg font-medium">참가자 명단</h2>

      <ul className="space-y-2">
        {names.map((name, i) => (
          <li key={i} className="flex items-center gap-2">
            <Input
              aria-label={`참가자 ${i + 1}`}
              value={name}
              onChange={e => edit(i, e.target.value)}
            />
            <Button variant="ghost" aria-label={`참가자 ${i + 1} 삭제`} onClick={() => remove(i)}>
              삭제
            </Button>
          </li>
        ))}
      </ul>

      <div className="flex items-center gap-2">
        <Button variant="outline" onClick={() => { setNames([...names, '']); setSaved(false) }}>
          추가
        </Button>
        <Button onClick={submit} disabled={save.isPending}>저장</Button>
      </div>

      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {saved && <p role="status" className="text-sm text-muted-foreground">저장됨</p>}
    </section>
  )
}
