import { type Split } from '../../core/api/client'
import { distance, duration, elevation, paceOrSpeed, type UnitSystem } from '../../core/format'

/**
 * The splits table, read verbatim from what the phone computed.
 *
 * Nothing here recalculates a split — not even the "is this one fast" bar, which is scaled
 * from the stored average speeds. One implementation of the metric means one thing to be
 * right or wrong, and it lives on the device (Constitution Principle I).
 */
export function SplitsTable({
  splits,
  sport,
  units,
}: {
  splits: Split[]
  sport: string
  units: UnitSystem
}) {
  const wanted = units === 'imperial' ? 'mi' : 'km'
  const rows = splits.filter((split) => split.unit === wanted)
  if (rows.length === 0) return null

  const fastest = rows.reduce((best, row) => Math.max(best, row.avgSpeedMps ?? 0), 0)

  return (
    <section className="m3-card hh-section" aria-labelledby="splits-heading">
      <h2 id="splits-heading" className="t-title-medium">
        Splits
      </h2>

      <table className="hh-table">
        <thead>
          <tr>
            <th scope="col">{wanted === 'mi' ? 'Mile' : 'Km'}</th>
            <th scope="col">Time</th>
            <th scope="col">{paceHeading(sport)}</th>
            <th scope="col" className="hh-table__bar-column">
              <span className="hh-visually-hidden">Relative speed</span>
            </th>
            <th scope="col">Elev</th>
            <th scope="col">HR</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((split) => {
            // The last split is usually a partial one; saying so beats showing a pace that
            // looks anomalous because it covered 300 m rather than a kilometre.
            const partial = split.distanceM < (wanted === 'mi' ? 1609.344 : 1000) * 0.95
            const share = fastest > 0 ? ((split.avgSpeedMps ?? 0) / fastest) * 100 : 0

            return (
              <tr key={`${split.unit}-${split.idx}`}>
                <th scope="row" className="numeric">
                  {split.idx + 1}
                  {partial && (
                    <span className="t-body-small"> · {distance(split.distanceM, units)}</span>
                  )}
                </th>
                <td className="numeric">{duration(split.movingSeconds ?? split.elapsedSeconds)}</td>
                <td className="numeric">{paceOrSpeed(split.avgSpeedMps, sport, units)}</td>
                <td className="hh-table__bar-column">
                  <span
                    className="hh-bar"
                    style={{ width: `${share}%` }}
                    aria-hidden="true"
                  />
                </td>
                <td className="numeric">
                  {split.elevationGainM === null ? '—' : elevation(split.elevationGainM, units)}
                </td>
                <td className="numeric">{split.avgHrBpm === null ? '—' : split.avgHrBpm}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </section>
  )
}

function paceHeading(sport: string): string {
  const speedSports = new Set(['cycling', 'ebiking', 'rowing', 'swimming', 'skiing', 'skating'])
  return speedSports.has(sport) ? 'Speed' : 'Pace'
}
