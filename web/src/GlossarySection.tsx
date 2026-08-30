import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import { fetchGlossary, pasteGlossary, updateTerm, type Term } from './api'

type Editing = { 기존표기: string; 표기: string; 뜻: string }

export function GlossarySection() {
  const queryClient = useQueryClient()
  const { data } = useQuery({ queryKey: ['glossary'], queryFn: fetchGlossary })

  const [text, setText] = useState('')
  const [summary, setSummary] = useState<string | null>(null)
  const [editing, setEditing] = useState<Editing | null>(null)
  const [error, setError] = useState<string | null>(null)

  const terms = [...(data ?? [])].sort((a, b) => a.표기.localeCompare(b.표기, 'ko'))

  const paste = useMutation({
    mutationFn: pasteGlossary,
    onSuccess: result => {
      // 미리보기는 없다 — 넣은 뒤 요약이 그 자리를 대신한다
      setSummary(`${result.added}개 추가, ${result.ignored}개 무시(이미 있음)`)
      setText('')
      queryClient.invalidateQueries({ queryKey: ['glossary'] })
    },
    onError: (e: Error) => setError(e.message),
  })

  const update = useMutation({
    mutationFn: ({ 기존표기, 표기, 뜻 }: Editing) =>
      updateTerm(기존표기, 표기.trim(), 뜻.trim() === '' ? null : 뜻.trim()),
    onSuccess: () => {
      setEditing(null)
      queryClient.invalidateQueries({ queryKey: ['glossary'] })
    },
    onError: (e: Error) => setError(e.message),
  })

  function startEditing(term: Term) {
    setError(null)
    setEditing({ 기존표기: term.표기, 표기: term.표기, 뜻: term.뜻 ?? '' })
  }

  return (
    <section className="space-y-4">
      <h2 className="text-lg font-medium">용어집</h2>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>표기</TableHead>
            <TableHead>뜻</TableHead>
            <TableHead className="w-32" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {terms.map(term => editing?.기존표기 === term.표기 ? (
            <TableRow key={term.표기}>
              <TableCell>
                <Input
                  aria-label="표기"
                  value={editing.표기}
                  onChange={e => setEditing({ ...editing, 표기: e.target.value })}
                />
              </TableCell>
              <TableCell>
                <Input
                  aria-label="뜻"
                  value={editing.뜻}
                  onChange={e => setEditing({ ...editing, 뜻: e.target.value })}
                />
              </TableCell>
              <TableCell className="flex gap-1">
                <Button onClick={() => update.mutate(editing)} disabled={update.isPending}>저장</Button>
                <Button variant="ghost" onClick={() => setEditing(null)}>취소</Button>
              </TableCell>
            </TableRow>
          ) : (
            <TableRow key={term.표기}>
              <TableCell>{term.표기}</TableCell>
              <TableCell>{term.뜻}</TableCell>
              <TableCell>
                <Button variant="outline" aria-label={`${term.표기} 수정`} onClick={() => startEditing(term)}>
                  수정
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}

      <div className="space-y-2">
        <label htmlFor="glossary-paste" className="text-sm font-medium">
          한 줄에 하나, 탭이나 콜론으로 표기와 뜻을 구분
        </label>
        <Textarea
          id="glossary-paste"
          rows={6}
          value={text}
          onChange={e => setText(e.target.value)}
        />
        <div className="flex items-center gap-3">
          <Button onClick={() => { setError(null); paste.mutate(text) }} disabled={paste.isPending}>
            넣기
          </Button>
          {summary && <span role="status" className="text-sm text-muted-foreground">{summary}</span>}
        </div>
      </div>
    </section>
  )
}
