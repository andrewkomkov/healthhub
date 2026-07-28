import { useEffect, useId, useMemo, useRef, useState } from 'react'
import uPlot from 'uplot'
import 'uplot/dist/uPlot.min.css'
import { axisRange } from './axisRange'
import { bucketMean, bucketize, finiteOf } from './buckets'
import { describePanel } from './describe'
import { useChartTheme } from './theme'

/**
 * The cursor-scrubbed chart on the activity detail screen.
 *
 * **One channel at a time, chosen with a chip.** This used to stack every channel the recording
 * had, one µPlot instance each, synced under a shared cursor. Five panels means each is a fifth
 * of the height, which is not enough to read a shape out of, and it means scrolling past four
 * charts to reach the fifth. One chart shown properly beats five shown badly; the chips are the
 * switch, they carry each channel's own colour, and the value under the pointer is the headline
 * above the plot rather than a number in a legend somewhere else.
 *
 * The series is the mean of each bucket — see `buckets.ts`, where the reversal from a min-max
 * envelope is argued, and `ChartSeries.kt`, which does the identical reduction on the phone.
 *
 * Two things it deliberately does not do. It does not use µPlot's legend — the readout is React,
 * directly above the series it describes. And it does not let a drag change the scale: dragging
 * selects a range for the statistics panel, which is what an athlete comparing a climb to the
 * rest of the ride actually wants.
 */

export interface ChartPanel {
  /** Channel name; also picks the colour, so a channel keeps its hue across every surface. */
  key: string
  label: string
  values: Float64Array
  format: (value: number) => string
  /** Shown when the pointer is away — usually the activity average. */
  summary?: string
  /**
   * A value the y axis may not drop below, whatever the channel did. Speed passes the moving
   * threshold: see `axisRange`, which is where the reasoning lives.
   */
  axisFloor?: number
}

export interface TelemetryChartsProps {
  x: Float64Array
  xFormat: (value: number) => string
  panels: ChartPanel[]
  /** Sample index under the cursor, or null when the pointer leaves the chart. */
  onCursor?: (index: number | null) => void
  /** Inclusive sample range, or null when the selection is cleared. */
  onSelect?: (range: { from: number; to: number } | null) => void
  /** Drives the header readout only; it never rebuilds the chart. */
  cursorIndex?: number | null
  plotHeight?: number
}

/** The Expressive line is heavier than the token's base weight; the token is still the source. */
const STROKE_EMPHASIS = 1.5

const FILL_TOP_ALPHA = 0.28

/** Dashed rather than solid: the reading is the shape of the curve, not the grid behind it. */
const GRID_DASH = [6, 10]

/**
 * A token colour at a given alpha.
 *
 * `color-mix` would be tidier, but this value is handed to a canvas gradient stop rather than to
 * CSS, and a gradient stop has to be a colour string the 2D context can parse itself. The token
 * sheet publishes hex, so hex is what this widens; anything else is passed through and simply
 * appears at full opacity rather than breaking the chart.
 */
function withAlpha(colour: string, alpha: number): string {
  const hex = colour.trim()
  if (!/^#[0-9a-f]{6}$/i.test(hex)) return hex
  const r = Number.parseInt(hex.slice(1, 3), 16)
  const g = Number.parseInt(hex.slice(3, 5), 16)
  const b = Number.parseInt(hex.slice(5, 7), 16)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

export function TelemetryCharts({
  x,
  xFormat,
  panels,
  onCursor,
  onSelect,
  cursorIndex = null,
  plotHeight = 220,
}: TelemetryChartsProps) {
  const theme = useChartTheme()
  const syncKey = useId()
  const host = useRef<HTMLDivElement>(null)

  // Callbacks live in refs so a parent re-rendering on every cursor move does not tear down and
  // rebuild the chart underneath the athlete's pointer.
  const cursorRef = useRef(onCursor)
  const selectRef = useRef(onSelect)
  cursorRef.current = onCursor
  selectRef.current = onSelect

  const keys = panels.map((panel) => panel.key).join(',')
  const [selectedKey, setSelectedKey] = useState(panels[0]?.key ?? '')
  // A preview object and the full one can offer different channels; a key that survived the swap
  // but no longer exists would leave the chart blank with a chip still lit next to it.
  const panel = panels.find((entry) => entry.key === selectedKey) ?? panels[0]
  useEffect(() => {
    if (panels.length > 0 && !panels.some((entry) => entry.key === selectedKey)) {
      setSelectedKey(panels[0]!.key)
    }
    // `keys` rather than `panels`, which is a new array on every render of the screen.
  }, [keys, panels, selectedKey])

  // The boundaries depend only on x, so every channel buckets identically — the chips must not
  // swap between series that disagree about where the ride's halfway point is.
  const buckets = useMemo(() => bucketize(x), [x])
  const series = useMemo(() => (panel ? bucketMean(panel.values, buckets) : []), [panel, buckets])
  const xs = useMemo(() => Array.from(buckets.x), [buckets])
  const description = useMemo(() => (panel ? describePanel(panel) : ''), [panel])

  useEffect(() => {
    const container = host.current
    const target = container?.querySelector<HTMLDivElement>('[data-plot]')
    if (!container || !target || !panel) return

    const colour = theme.channel(panel.key)

    /**
     * The vertical wash under the line, anchored to the top of the *series* rather than the top
     * of the plot. A walk whose speed never leaves the bottom third of its own axis would
     * otherwise get only the transparent tail of the gradient and show no fill at all.
     *
     * Returned as a function because µPlot calls it after layout, which is the only moment the
     * plotting area's pixel bounds are known — and it re-calls it on resize, so the gradient
     * follows the chart instead of being baked at the width it was born at.
     */
    const wash = (u: uPlot, seriesIndex: number) => {
      const scale = u.series[seriesIndex]?.scale ?? 'y'
      const max = u.scales[scale]?.max
      const top = max === undefined ? u.bbox.top : u.valToPos(max, scale, true)
      const gradient = u.ctx.createLinearGradient(0, top, 0, u.bbox.top + u.bbox.height)
      gradient.addColorStop(0, withAlpha(colour, FILL_TOP_ALPHA))
      gradient.addColorStop(1, withAlpha(colour, 0))
      return gradient
    }

    const chart = new uPlot(
      {
        width: container.clientWidth || 720,
        height: plotHeight,
        padding: [8, 8, 0, 0],
        legend: { show: false },
        cursor: {
          sync: { key: syncKey, scales: ['x', null] },
          drag: { x: true, y: false, setScale: false, dist: 4 },
          // The ring is the bed, not the page: the dot sits on the tonal container the plot is
          // drawn in, and a gap ring only reads as a gap if it is that colour.
          points: { size: 9, width: 3, stroke: () => colour, fill: () => theme.bed },
        },
        scales: {
          x: { time: false },
          // Fixed to the trimmed range rather than left to µPlot's auto-fit, which scales to the
          // extremes and is what let one stopped sample flatten an entire walk.
          y: (() => {
            const bounds = axisRange(finiteOf(series), panel.axisFloor)
            return bounds ? { range: () => bounds } : {}
          })(),
        },
        axes: [
          {
            stroke: theme.inkMuted,
            font: theme.axisFont,
            grid: { stroke: theme.gridline, width: 1, dash: GRID_DASH },
            ticks: { stroke: theme.axis, width: 1, size: 4 },
            values: (_u, splits) => splits.map((value) => xFormat(value)),
          },
          {
            stroke: theme.inkMuted,
            font: theme.axisFont,
            // The gutter is measured in pixels, so it has to follow the reader's font size:
            // "1:23:45" at 200% text does not fit in a gutter cut for an 11px label.
            size: Math.max(52, Math.round(theme.axisLabelPx * 5)),
            grid: { stroke: theme.gridline, width: 1, dash: GRID_DASH },
            ticks: { show: false },
            values: (_u, splits) => splits.map((value) => panel.format(value)),
          },
        ],
        series: [
          {},
          {
            label: panel.label,
            stroke: colour,
            // Heavier than the token's base weight, round-capped, over a wash of the channel's
            // own colour. The token is still the source; the Expressive treatment is the
            // multiplier, and it is the same 1.5 the Kotlin side applies.
            width: theme.lineWidth * STROKE_EMPHASIS,
            // µPlot exposes the cap but not the join; it already joins round internally, so this
            // is the whole of the round-stroke story on the web side.
            cap: 'round',
            fill: wash,
            points: { show: false },
            spanGaps: false,
          },
        ],
        hooks: {
          setCursor: [
            (u) => {
              const bucket = u.cursor.idx
              // The cursor lands on a bucket; everything downstream — the readout, the marker on
              // the map, the range statistics — counts in samples of the ride.
              cursorRef.current?.(
                bucket === null || bucket === undefined ? null : (buckets.sample[bucket] ?? null),
              )
            },
          ],
          setSelect: [
            (u) => {
              if (u.select.width <= 0) {
                selectRef.current?.(null)
                return
              }
              const from = buckets.from[u.posToIdx(u.select.left)] ?? 0
              const to = buckets.to[u.posToIdx(u.select.left + u.select.width)] ?? 1
              selectRef.current?.({ from, to: Math.max(from, to - 1) })
            },
          ],
        },
      },
      [xs, series] as unknown as uPlot.AlignedData,
      target,
    )

    const observer = new ResizeObserver(() => {
      const next = container.clientWidth
      if (next > 0) chart.setSize({ width: next, height: plotHeight })
    })
    observer.observe(container)

    return () => {
      observer.disconnect()
      chart.destroy()
    }
    // Rebuilt when the data, the chosen channel or the palette changes — the three things µPlot
    // cannot be told about after construction.
  }, [xs, series, buckets, panel, plotHeight, syncKey, theme, xFormat])

  if (!panel) return null

  const at = cursorIndex === null ? Number.NaN : (panel.values[cursorIndex] ?? Number.NaN)
  const where =
    cursorIndex === null
      ? panel.summary
        ? `${panel.label}, average`
        : panel.label
      : `${panel.label} at ${xFormat(x[Math.min(cursorIndex, x.length - 1)] ?? 0)}`

  return (
    <div ref={host} className="hh-chart">
      {panels.length > 1 && (
        <div className="hh-chips hh-chips--wrap" role="group" aria-label="Chart channel">
          {panels.map((entry) => (
            <button
              key={entry.key}
              type="button"
              className={`hh-chip${entry.key === panel.key ? ' hh-chip--selected' : ''}`}
              aria-pressed={entry.key === panel.key}
              onClick={() => setSelectedKey(entry.key)}
            >
              {/* The channel's own colour, not a generic icon: the swatch is the same identity
                  the line and the readout carry, so the chip needs no other mark. */}
              <span
                className="hh-chart__swatch"
                style={{ background: `var(--chart-channel-${entry.key})` }}
                aria-hidden="true"
              />
              {entry.label}
            </button>
          ))}
        </div>
      )}

      <div className="hh-chart__value">
        <span
          className="t-headline-large-emphasized numeric"
          style={{ color: `var(--chart-channel-${panel.key})` }}
        >
          {Number.isNaN(at) ? (panel.summary ?? '—') : panel.format(at)}
        </span>
        <span className="t-label-medium hh-chart__where">{where}</span>
      </div>

      <div data-plot className="hh-chart__canvas" role="img" aria-label={description} />
    </div>
  )
}
