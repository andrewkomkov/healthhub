import { useCallback, useMemo, useState } from 'react'
import { useLocale, useMessages, type Bundle } from '../../core/i18n'
import { type User } from '../../core/api/client'
import { TelemetryCharts, type ChartPanel } from '../../core/charts/TelemetryCharts'
import { Icon, type IconName } from '../../core/m3e/Icon'
import { RouteMap } from '../../core/map/RouteMap'
import { routeGeometry } from '../../core/map/route'
import {
  distance,
  duration,
  elevation,
  localDate,
  paceOrSpeed,
  sportLabel,
  heartRate,
  unitLabels,
} from '../../core/format'
import {
  cumulativeDistance,
  rangeStats,
  MOVING_SPEED_THRESHOLD_MPS,
} from '../../core/telemetry/analysis'
import { SplitsTable } from './SplitsTable'
import { ZoneDistribution } from './ZoneDistribution'
import { useActivityTelemetry } from './useTelemetry'

const MESSAGES = {
  en: {
    loading: "Loading…",
    summary: "Summary",
    telemetry: "Telemetry",
    route: "Route",
    chartAxis: "Chart axis",
    axisTime: "Time",
    axisDistance: "Distance",
    distance: "Distance",
    moving: "Moving",
    elapsed: "Elapsed",
    avgPace: "Avg pace",
    avgSpeed: "Avg speed",
    bestPace: "Best pace",
    maxSpeed: "Max speed",
    elevGain: "Elev gain",
    avgHr: "Avg HR",
    maxHr: "Max HR",
    avgPower: "Avg power",
    calories: "Calories",
    selection: "Selection",
    noRoute: "No route to draw",
    noSamples: "No per-second data for this workout",
    preview: "Preview resolution — loading full detail…",
    channelElevation: "Elevation",
    channelSpeed: "Speed",
    channelPace: "Pace",
    channelHr: "Heart rate",
    channelCadence: "Cadence",
    channelPower: "Power",
  },
  ru: {
    loading: "Загружаем…",
    summary: "Сводка",
    telemetry: "Телеметрия",
    route: "Трек",
    chartAxis: "Ось графика",
    axisTime: "Время",
    axisDistance: "Дистанция",
    distance: "Дистанция",
    moving: "В движении",
    elapsed: "Общее время",
    avgPace: "Средний темп",
    avgSpeed: "Средняя скорость",
    bestPace: "Лучший темп",
    maxSpeed: "Макс. скорость",
    elevGain: "Набор высоты",
    avgHr: "Средний пульс",
    maxHr: "Макс. пульс",
    avgPower: "Средняя мощность",
    calories: "Калории",
    selection: "Выделение",
    noRoute: "Трек нарисовать не из чего",
    noSamples: "У этой тренировки нет посекундных данных",
    preview: "Предварительное разрешение — загружается полное…",
    channelElevation: "Высота",
    channelSpeed: "Скорость",
    channelPace: "Темп",
    channelHr: "Пульс",
    channelCadence: "Каденс",
    channelPower: "Мощность",
  },
} satisfies Bundle<Record<string, string>>


/** Which channels get a panel, in the order they are stacked, and how each one reads. */
const PANEL_ORDER = ['elevation', 'speed', 'hr', 'cadence', 'power'] as const

type PanelKey = (typeof PANEL_ORDER)[number]

/**
 * A channel's name, as a key into the screen's own bundle rather than as a word.
 *
 * The map is module-level and the lookup is not — resolving it here would have frozen the
 * language at import time, which is the same mistake as reading `Locale.getDefault()` into a
 * `val` on the phone.
 */
const PANEL_LABELS: Record<PanelKey, keyof (typeof MESSAGES)['en']> = {
  elevation: 'channelElevation',
  speed: 'channelSpeed',
  hr: 'channelHr',
  cadence: 'channelCadence',
  power: 'channelPower',
}

const SPEED_SPORTS = new Set(['cycling', 'ebiking', 'rowing', 'swimming', 'skiing', 'skating'])

/**
 * One figure, in its own container, under its icon and name.
 *
 * The value carries the Expressive emphasised weight and the label does not: the thing that has
 * to be readable at a glance is the number. The icon is what makes the tile findable without
 * reading it at all — the phone draws the identical tile with the identical mark.
 */
function Tile({ icon, label, value }: { icon: IconName; label: string; value: string }) {
  return (
    <div className="hh-tile">
      <span className="t-label-medium hh-tile__label">
        <Icon name={icon} />
        {label}
      </span>
      <span className="t-headline-large-emphasized numeric">{value}</span>
    </div>
  )
}

/** A figure and its name, with no container: inside a card, tiles would be boxes in a box. */
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
  const t = useMessages(MESSAGES)
  // One lookup for the whole screen: every tile and every chart axis reads it.
  const labels = unitLabels(useLocale())
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
          label: key === 'speed' && pacey ? t.channelPace : t[PANEL_LABELS[key]],
          values,
          format: formatFor(key),
          summary: summaries[key],
          // Standing still is not a pace. Without this the axis of a ride with traffic lights
          // in it runs down to two hours per kilometre and the ride itself is a sliver.
          ...(key === 'speed' ? { axisFloor: MOVING_SPEED_THRESHOLD_MPS } : {}),
        },
      ]
    })
  }, [channels, activity, sport, units, pacey, formatFor])

  // Computed here rather than inside the map, because the choice between the map and the card
  // that stands in for it is this screen's to make — and it has to be made from the same answer
  // the map draws from. `fixCount` is what lets the card say which of the three cases it is.
  const routeShape = useMemo(
    () => routeGeometry(channels.lat, channels.lon, channels.time),
    [channels.lat, channels.lon, channels.time],
  )
  const fixCount = useMemo(() => {
    const { lat, lon } = channels
    if (!lat || !lon) return 0
    let seen = 0
    for (let i = 0; i < Math.min(lat.length, lon.length); i++) {
      if (!Number.isNaN(lat[i]!) && !Number.isNaN(lon[i]!)) seen++
    }
    return seen
  }, [channels])

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
        <p className="t-body-medium">{t.loading}</p>
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

        <section className="m3-card hh-section" aria-label={t.summary}>
          <div className="hh-stats-tiles">
            <Tile icon="route" label={t.distance} value={distance(activity.distanceM, units, labels)} />
            <Tile
              icon="timer"
              label={t.moving}
              value={duration(activity.movingSeconds ?? activity.elapsedSeconds)}
            />
            <Tile icon="schedule" label={t.elapsed} value={duration(activity.elapsedSeconds)} />
            <Tile
              icon="speed"
              label={pacey ? t.avgPace : t.avgSpeed}
              value={paceOrSpeed(activity.avgSpeedMps, sport, units)}
            />
            {activity.maxSpeedMps !== null && (
              <Tile
                icon="bolt"
                label={pacey ? t.bestPace : t.maxSpeed}
                value={paceOrSpeed(activity.maxSpeedMps, sport, units)}
              />
            )}
            {activity.elevationGainM !== null && (
              <Tile
                icon="terrain"
                label={t.elevGain}
                value={elevation(activity.elevationGainM, units)}
              />
            )}
            {activity.avgHrBpm !== null && (
              <Tile icon="heart" label={t.avgHr} value={heartRate(activity.avgHrBpm, labels)} />
            )}
            {activity.maxHrBpm !== null && (
              <Tile icon="pulse" label={t.maxHr} value={heartRate(activity.maxHrBpm, labels)} />
            )}
            {activity.avgPowerW !== null && (
              <Tile icon="bolt" label={t.avgPower} value={`${Math.round(activity.avgPowerW)} ${labels.watts}`} />
            )}
            {activity.caloriesKcal !== null && (
              <Tile
                icon="flame"
                label={t.calories}
                value={`${Math.round(activity.caloriesKcal)} kcal`}
              />
            )}
          </div>
        </section>

        {routeShape.bounds !== null ? (
          <RouteMap
            geometry={routeShape}
            lat={channels.lat}
            lon={channels.lon}
            cursorIndex={cursorIndex}
          />
        ) : (
          <section className="m3-empty" aria-label={t.route}>
            <h2 className="t-title-medium">{t.noRoute}</h2>
            <p className="t-body-medium">
              {/* Three different situations, and telling them apart is the whole point of this
                  card. A recording whose track is a single fix used to render nothing at all —
                  no map, no sentence — because the screen asked "are there positions" and the
                  map asked "is there a line", and the two disagreed. */}
              {fixCount > 0
                ? `This recording stored ${fixCount === 1 ? 'a single position' : `${fixCount} positions`}, which is not enough to draw a track. The app that wrote it recorded the start and nothing after it.`
                : activity.hasGps
                  ? 'This workout has GPS, but the track itself has not been imported yet.'
                  : 'This was recorded indoors, or by an app that stored no positions. Everything the sensors did record is below.'}
            </p>
          </section>
        )}

        {panels.length > 0 && xValues ? (
          <section className="m3-card hh-section" aria-label={t.telemetry}>
            <div className="hh-section__bar">
              <div className="hh-chips" role="group" aria-label={t.chartAxis}>
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
                <span className="t-body-small">{t.preview}</span>
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
                  <h3 className="t-title-medium">{t.selection}</h3>
                  <button className="m3-button m3-button--text" onClick={() => setSelection(null)}>
                    Clear
                  </button>
                </div>
                <div className="hh-stats-grid">
                  <Stat label={t.distance} value={distance(selectionStats.distanceM, units, labels)} />
                  <Stat label={t.elapsed} value={duration(selectionStats.elapsedSeconds)} />
                  <Stat label={t.moving} value={duration(selectionStats.movingSeconds)} />
                  <Stat
                    label={pacey ? t.avgPace : t.avgSpeed}
                    value={paceOrSpeed(selectionStats.avgSpeedMps, sport, units)}
                  />
                  <Stat
                    label={t.elevGain}
                    value={elevation(selectionStats.elevationGainM, units)}
                  />
                  <Stat
                    label={t.avgHr}
                    value={
                      selectionStats.avgHrBpm === null
                        ? '—'
                        : `${Math.round(selectionStats.avgHrBpm)} bpm`
                    }
                  />
                  {selectionStats.avgPowerW !== null && (
                    <Stat
                      label={t.avgPower}
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
            <section className="m3-empty" aria-label={t.telemetry}>
              <h2 className="t-title-medium">{t.noSamples}</h2>
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
