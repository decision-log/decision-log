import { ParticipantsSection } from './ParticipantsSection'
import { GlossarySection } from './GlossarySection'

/** 화면 한 장, 두 섹션. 라우팅은 화면이 늘어날 때 정한다. */
export function SettingsPage() {
  return (
    <section className="space-y-10">
      <h1 className="text-2xl font-semibold">설정</h1>
      <ParticipantsSection />
      <GlossarySection />
    </section>
  )
}
