import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import { fetchGlossary, pasteGlossary, updateTerm, type Term } from './api'

type Editing = { oldSpelling: string; spelling: string; meaning: string }

export function GlossarySection() {
  const queryClient = useQueryClient()
  const { data } = useQuery({ queryKey: ['glossary'], queryFn: fetchGlossary })

  const [text, setText] = useState('')
  const [summary, setSummary] = useState<string | null>(null)
  const [editing, setEditing] = useState<Editing | null>(null)
  const [error, setError] = useState<string | null>(null)

  const terms = [...(data ?? [])].sort((a, b) => a.spelling.localeCompare(b.spelling, 'ko'))

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
    mutationFn: ({ oldSpelling, spelling, meaning }: Editing) =>
      updateTerm(oldSpelling, spelling.trim(), meaning.trim() === '' ? null : meaning.trim()),
    onSuccess: () => {
      setEditing(null)
      queryClient.invalidateQueries({ queryKey: ['glossary'] })
    },
    onError: (e: Error) => setError(e.message),
  })

  function startEditing(term: Term) {
    setError(null)
    setEditing({ oldSpelling: term.spelling, spelling: term.spelling, meaning: term.meaning ?? '' })
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
          {terms.map(term => editing?.oldSpelling === term.spelling ? (
            <TableRow key={term.spelling}>
              <TableCell>
                <Input
                  aria-label="표기"
                  value={editing.spelling}
                  onChange={e => setEditing({ ...editing, spelling: e.target.value })}
                />
              </TableCell>
              <TableCell>
                <Input
                  aria-label="뜻"
                  value={editing.meaning}
                  onChange={e => setEditing({ ...editing, meaning: e.target.value })}
                />
              </TableCell>
              <TableCell className="flex gap-1">
                <Button onClick={() => update.mutate(editing)} disabled={update.isPending}>저장</Button>
                <Button variant="ghost" onClick={() => setEditing(null)}>취소</Button>
              </TableCell>
            </TableRow>
          ) : (
            <TableRow key={term.spelling}>
              <TableCell>{term.spelling}</TableCell>
              <TableCell>{term.meaning}</TableCell>
              <TableCell>
                <Button variant="outline" aria-label={`${term.spelling} 수정`} onClick={() => startEditing(term)}>
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
          한 줄에 하나, 탭이나 콜론으로 spelling와 meaning을 구분
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
