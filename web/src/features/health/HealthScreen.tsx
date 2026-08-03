import { type User } from '../../core/api/client'
import { useMessages, type Bundle } from '../../core/i18n'
import { HistoryLab } from '../history/HistoryLab'
// Named `RecoveryPanel` rather than `Recovery`: the rules it draws live in `recovery.ts`,
// and on a case-insensitive filesystem `Recovery.tsx` and `recovery.ts` are the same path.
import { RecoveryPanel } from './RecoveryPanel'
import './health.css'

const MESSAGES = {
  en: {
    title: 'Health',
  },
  ru: {
    title: 'Здоровье',
  },
} satisfies Bundle<Record<string, string>>


/**
 * Health, recovery, and the whole-history archive.
 *
 * Two things share this route because they are the same question at two grains: what has all of
 * this added up to? The archive half is DuckDB over Parquet in the athlete's own bucket (roadmap
 * session 5); the recovery half reads `/api/health-records` (not `/api/health`, which is the
 * Worker's liveness probe).
 *
 * Both render figures the phone computed. The one thing derived in the browser is readiness,
 * which has no field anywhere on the API and must not have one — deriving it at the edge would
 * put arithmetic on a server. `recovery.ts` is the deliberate twin of `feature/health/
 * Readiness.kt`, constant for constant, so the two clients cannot tell an athlete two different
 * things about the same morning.
 */
export function HealthScreen({ user }: { user: User }) {
  const t = useMessages(MESSAGES)
  return (
    <>
      <header className="m3-app-bar">
        <h1 className="t-title-large-emphasized">{t.title}</h1>
      </header>

      <div className="m3-page">
        <HistoryLab user={user} />

        <RecoveryPanel />
      </div>
    </>
  )
}
