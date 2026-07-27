import { useCallback, useMemo, useState } from 'react'
import { type User } from '../../core/api/client'
import { TelemetryCharts, type ChartPanel } from '../../core/charts/TelemetryCharts'
import { RouteMap } from '../../core/map/RouteMap'
import {
  distance,
  duration,
  elevation,
  localDate,
  paceOrSpeed,
  sportLabel,
} from '../../core/format'
import { cumulativeDistance, rangeStats } from '../../core/telemetry/analysis'
import { SplitsTable } from './SplitsTable'
import { ZoneDistribution } from './ZoneDistribution'
import { useActivityTelemetry } from './useTelemetry'

/** Which channels get a panel, in the order they are stacked, and how each one reads. */
const PANEL_ORDER = ['elevation', 'speed', 'hr', 'cadence', 'power'] as const

type PanelKey = (typeof PANEL_ORDER)[number]

const PANEL_LABELS: Record<PanelKey, string> = {
  elevation: 'Elevation',
  speed: 'Speed',
  hr: 'Heart rate',
  cadence: 'Cadence',
  power: 'Power',
}

const SPEED_SPORTS = new Set(['cycling', 'ebiking', 'rowing', 'swimming', 'skiing', 'skating'])

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="hh-stat">
      <span className="t-label-medium">{label}</span>
      <span className="t-title-medium numeric">{value}</span>
    </div>
  )
}

export function ActivityScreen({
  id,
  user,
  onBack,
}: {
  id: string
  user: User
  onBack: () => void
}) {
  const { activity, telemetry, resolution, loading, error } = useActivityTelemetry(id)
  const [axis, setAxis] = useState<'time' | 'distance'>('time')
  const [cursorIndex, setCursorIndex] = useState<number | null>(null)
  const [selection, setSelection] = useState<{ from: number; to: number } | null>(null)

  const units = user.unitSystem
  const sport = activity?.sport ?? 'workout'
  const pacey = !SPEED_SPORTS.has(sport)

  const channels = useMemo(() => {
    const values = (name: string) => telemetry?.values(name) ?? null
    const time = values('t')
    const lat = values('lat')
    const lon = values('lon')
    const speed = values('speed')
    return {
      time,
      lat,
      lon,
      speed,
      hr: values('hr'),
      power: values('power'),
      cadence: values('cadence'),
      elevation: values('elevation'),
      distance: cumulativeDistance(time, lat, lon, speed, activity?.distanceM ?? null),
    }
  }, [telemetry, activity?.distanceM])

  const formatFor = useCallback(
    (key: PanelKey) => {
      switch (key) {
        case 'elevation':
          return (value: number) => elevation(value, units)
        case 'speed':
          return (value: number) => paceOrSpeed(value, sport, units)
        case 'hr':
          return (value: number) => `${Math.round(value)} bpm`
        case 'cadence':
          return (value: number) => `${Math.round(value)} rpm`
        case 'power':
          return (value: number) => `${Math.round(value)} W`
      }
    },
    [sport, units],
  )

  const panels = useMemo<ChartPanel[]>(() => {
    const summaries: Partial<Record<PanelKey, string | undefined>> = {
      speed: activity ? paceOrSpeed(activity.avgSpeedMps, sport, units) : undefined,
      hr: activity?.avgHrBpm ? `${activity.avgHrBpm} bpm` : undefined,
      power: activity?.avgPowerW ? `${Math.round(activity.avgPowerW)} W` : undefined,
      cadence: activity?.avgCadenceRpm ? `${Math.round(activity.avgCadenceRpm)} rpm` : undefined,
      elevation: activity?.elevationGainM ? `+${elevation(activity.elevationGainM, units)}` : undefined,
    }

    return PANEL_ORDER.flatMap((key) => {
      const values = channels[key]
      // An absent channel is a channel the source never recorded. It gets no panel at all —
      // an empty axis would claim the sensor was there and read zero.
      if (!values || values.length === 0) return []
      return [
        {
          key,
          label: key === 'speed' && pacey ? 'Pace' : PANEL_LABELS[key],
          values,
          format: formatFor(key),
          summary: summaries[key],
        },
      ]
    })
  }, [channels, activity, sport, units, pacey, formatFor])

  const hasDistanceAxis = channels.distance !== null
  const activeAxis = hasDistanceAxis ? axis : 'time'

  const xValues = useMemo(() => {
    if (activeAxis === 'distance' && channels.distance) return channels.distance
    const time = channels.time
    if (!time) return null
    // µPlot wants an increasing numeric axis; seconds keep the tick labels honest.
    const seconds = new Float64Array(time.length)
    for (let i = 0; i < time.length; i++) seconds[i] = time[i]! / 1000
    return seconds
  }, [activeAxis, channels.distance, channels.time])

  const xFormat = useCallback(
    (value: number) => (activeAxis === 'distance' ? distance(value, units) : duration(value)),
    [activeAxis, units],
  )

  const selectionStats = useMemo(() => {
    if (!selection || !channels.time) return null
    return rangeStats(channels, selection.from, selection.to)
  }, [selection, channels])

  if (loading) {
    return (
      <div className="m3-page">
        <p className="t-body-medium">Loading…</p>
      </div>
    )
  }

  if (error || !activity) {
    return (
      <div className="m3-page">
        <button className="m3-button m3-button--text" onClick={onBack}>
          Back to activities
        </button>
        <p className="m3-error t-body-medium" role="alert">
          {error ?? 'Could not load this activity.'}
        </p>
      </div>
    )
  }

  return (
    <>
      <header className="m3-app-bar">
        <button className="m3-button m3-button--text" onClick={onBack}>
          ← Activities
        </button>
        <span className="t-label-medium">{sportLabel(sport)}</span>
      </header>

      <div className="m3-page">
        <header className="hh-detail-header">
          <span className="t-label-medium">
            {localDate(activity.startTime, activity.tzOffsetMinutes)}
          </span>
          <h1 className="t-headline-medium">{activity.title}</h1>
          {activity.description && <p className="t-body-medium">{activity.description}</p>}
        </header>

        <section className="m3-card hh-section" aria-label="Summary">
          <div className="hh-stats-grid">
            <Stat label="Distance" value={distance(activity.distanceM, units)} />
            <Stat label="Moving" value={duration(activity.movingSeconds ?? activity.elapsedSeconds)} />
            <Stat label="Elapsed" value={duration(activity.elapsedSeconds)} />
            <Stat
              label={pacey ? 'Avg pace' : 'Avg speed'}
              value={paceOrSpeed(activity.avgSpeedMps, sport, units)}
            />
            {activity.maxSpeedMps !== null && (
              <Stat
                label={pacey ? 'Best pace' : 'Max speed'}
                value={paceOrSpeed(activity.maxSpeedMps, sport, units)}
              />
            )}
            {activity.elevationGainM !== null && (
              <Stat label="Elev gain" value={elevation(activity.elevationGainM, units)} />
            )}
            {activity.avgHrBpm !== null && <Stat label="Avg HR" value={`${activity.avgHrBpm} bpm`} />}
            {activity.maxHrBpm !== null && <Stat label="Max HR" value={`${activity.maxHrBpm} bpm`} />}
            {activity.avgPowerW !== null && (
              <Stat label="Avg power" value={`${Math.round(activity.avgPowerW)} W`} />
            )}
            {activity.caloriesKcal !== null && (
              <Stat label="Calories" value={`${Math.round(activity.caloriesKcal)} kcal`} />
            )}
          </div>
        </section>

        {channels.lat && channels.lon ? (
          <RouteMap
            lat={channels.lat}
            lon={channels.lon}
            time={channels.time}
            cursorIndex={cursorIndex}
          />
        ) : (
          <section className="m3-empty" aria-label="Route">
            <h2 className="t-title-medium">No route was recorded</h2>
            <p className="t-body-medium">
              {activity.hasGps
                ? 'This workout has GPS, but the track itself has not been imported yet.'
                : 'This was recorded indoors, or by an app that stored no positions. Everything the sensors did record is below.'}
            </p>
          </section>
        )}

        {panels.length > 0 && xValues ? (
          <section className="m3-card hh-section" aria-label="Telemetry">
            <div className="hh-section__bar">
              <div className="hh-chips" role="group" aria-label="Chart axis">
                <button
                  className={`hh-chip${activeAxis === 'time' ? ' hh-chip--selected' : ''}`}
                  aria-pressed={activeAxis === 'time'}
                  onClick={() => setAxis('time')}
                >
                  Time
                </button>
                <button
                  className={`hh-chip${activeAxis === 'distance' ? ' hh-chip--selected' : ''}`}
                  aria-pressed={activeAxis === 'distance'}
                  onClick={() => setAxis('distance')}
                  disabled={!hasDistanceAxis}
                >
                  Distance
                </button>
              </div>
              {resolution === 'preview' && (
                <span className="t-body-small">Preview resolution — loading full detail…</span>
              )}
            </div>

            <TelemetryCharts
              x={xValues}
              xFormat={xFormat}
              panels={panels}
              cursorIndex={cursorIndex}
              onCursor={setCursorIndex}
              onSelect={setSelection}
            />

            {selectionStats ? (
              <div className="hh-selection">
                <div className="hh-section__bar">
                  <h3 className="t-title-medium">Selection</h3>
                  <button className="m3-button m3-button--text" onClick={() => setSelection(null)}>
                    Clear
                  </button>
                </div>
                <div className="hh-stats-grid">
                  <Stat label="Distance" value={distance(selectionStats.distanceM, units)} />
                  <Stat label="Elapsed" value={duration(selectionStats.elapsedSeconds)} />
                  <Stat label="Moving" value={duration(selectionStats.movingSeconds)} />
                  <Stat
                    label={pacey ? 'Avg pace' : 'Avg speed'}
                    value={paceOrSpeed(selectionStats.avgSpeedMps, sport, units)}
                  />
                  <Stat
                    label="Elev gain"
                    value={elevation(selectionStats.elevationGainM, units)}
                  />
                  <Stat
                    label="Avg HR"
                    value={
                      selectionStats.avgHrBpm === null
                        ? '—'
                        : `${Math.round(selectionStats.avgHrBpm)} bpm`
                    }
                  />
                  {selectionStats.avgPowerW !== null && (
                    <Stat
                      label="Avg power"
                      value={`${Math.round(selectionStats.avgPowerW)} W`}
                    />
                  )}
                </div>
              </div>
            ) : (
              <p className="t-body-small">
                Drag across a chart to see the numbers for just that stretch.
              </p>
            )}
          </section>
        ) : (
          resolution === 'none' && (
            <section className="m3-empty" aria-label="Telemetry">
              <h2 className="t-title-medium">No per-second data for this workout</h2>
              <p className="t-body-medium">
                The source recorded a summary but no samples, so there is nothing to plot. The
                figures above are what it did report.
              </p>
            </section>
          )
        )}

        <SplitsTable splits={activity.splits} sport={sport} units={units} />
        <ZoneDistribution zones={activity.zones} />
      </div>
    </>
  )
}
