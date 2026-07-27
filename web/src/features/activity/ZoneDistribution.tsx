import { type Zone } from '../../core/api/client'
import { useChartTheme } from '../../core/charts/theme'
import { duration } from '../../core/format'

/**
 * Time in heart-rate and power zones.
 *
 * Zones are ordinal — zone 4 is harder than zone 3 — so they are coloured from the sequential
 * ramp in the token file rather than from the categorical series palette. Using eight
 * unrelated hues here would imply the zones are unrelated categories, which is the opposite
 * of what they are.
 */
export function ZoneDistribution({ zones }: { zones: Zone[] }) {
  const theme = useChartTheme()
  const kinds = ['hr', 'power'] as const
  const present = kinds.filter((kind) => zones.some((zone) => zone.kind === kind))
  if (present.length === 0) return null

  return (
    <section className="m3-card hh-section" aria-labelledby="zones-heading">
      <h2 id="zones-heading" className="t-title-medium">
        Zones
      </h2>

      {present.map((kind) => {
        const rows = zones.filter((zone) => zone.kind === kind).sort((a, b) => a.zoneIndex - b.zoneIndex)
        const total = rows.reduce((sum, zone) => sum + zone.seconds, 0)
        const longest = rows.reduce((max, zone) => Math.max(max, zone.seconds), 0)

        return (
          <div key={kind} className="hh-zones">
            <h3 className="t-label-medium">{kind === 'hr' ? 'Heart rate' : 'Power'}</h3>
            {rows.map((zone) => (
              <div key={zone.zoneIndex} className="hh-zone-row">
                <span className="t-label-medium">Z{zone.zoneIndex + 1}</span>
                <span className="t-body-small numeric hh-zone-row__bound">
                  {zone.lowerBound}
                  {zone.upperBound === null ? '+' : `–${zone.upperBound}`}
                </span>
                <span className="hh-zone-row__track">
                  <span
                    className="hh-zone-row__fill"
                    style={{
                      width: longest > 0 ? `${(zone.seconds / longest) * 100}%` : 0,
                      // Ordinal intensity: the ramp is spread across however many zones there
                      // are, inside the span that stays legible on the current surface.
                      background: theme.ordinal(zone.zoneIndex, rows.length),
                    }}
                  />
                </span>
                <span className="numeric t-body-medium">{duration(zone.seconds)}</span>
                <span className="numeric t-body-small">
                  {total > 0 ? `${Math.round((zone.seconds / total) * 100)}%` : '—'}
                </span>
              </div>
            ))}
          </div>
        )
      })}
    </section>
  )
}
