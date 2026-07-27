import { type User } from '../../core/api/client'
import { HistoryLab } from '../history/HistoryLab'

/**
 * Health, recovery, and the whole-history archive.
 *
 * Two things share this route because they are the same question at two grains: what has all of
 * this added up to? The archive half is built — DuckDB over Parquet in the athlete's own bucket
 * (roadmap session 5). The recovery half is not: sleep stages, HRV and readiness arrive with
 * session 4, read from `/api/health-records` (not `/api/health`, which is the Worker's liveness
 * probe). Both render figures the phone computed; neither derives one here.
 */
export function HealthScreen({ user, onBack }: { user: User; onBack: () => void }) {
  return (
    <>
      <header className="m3-app-bar">
        <button className="m3-button m3-button--text" onClick={onBack}>
          ← Activities
        </button>
        <h1 className="t-title-large">Health</h1>
      </header>

      <div className="m3-page">
        <HistoryLab user={user} />

        <section className="m3-card hh-section" aria-labelledby="recovery-heading">
          <div className="hh-detail-header">
            <h2 id="recovery-heading" className="t-title-medium">
              Sleep and recovery
            </h2>
            <p className="t-body-medium">
              Sleep stages, heart-rate variability, resting heart rate and the rest of what
              Health Connect holds will appear here once the phone starts sending them.
            </p>
          </div>
        </section>
      </div>
    </>
  )
}
