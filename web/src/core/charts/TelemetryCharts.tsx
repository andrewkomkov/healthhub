import { useEffect, useId, useMemo, useRef } from 'react'
import uPlot from 'uplot'
import 'uplot/dist/uPlot.min.css'
import { axisRange } from './axisRange'
import { describePanel } from './describe'
import { useChartTheme } from './theme'

/**
 * The stacked, aligned, cursor-synced chart stack on the activity detail screen.
 *
 * One µPlot instance per channel rather than one chart with several y-axes: channels have
 * incomparable ranges (150 bpm and 4 m/s share no axis worth drawing) and stacking them keeps
 * every series readable at full height while `uPlot.sync` makes the whole stack behave as one
 * chart under the cursor.
 *
 * Two things it deliberately does not do. It does not use µPlot's legend — the readout lives
 * in the panel header as React, direct-labelled next to the series it describes. And it does
 * not let a drag change the scale: dragging selects a range for the statistics panel, which is
 * what an athlete comparing a climb to the rest of the ride actually wants.
 */

export interface ChartPanel {
  /** Channel name; also picks the colour, so a channel keeps its hue across every surface. */
  key: string
  label: string
  values: Float64Array
  format: (value: number) => string
  /** Shown in the header when the pointer is away — usually the activity average. */
  summary?: string
}

export interface TelemetryChartsProps {
  x: Float64Array
  xFormat: (value: number) => string
  panels: ChartPanel[]
  /** Sample index under the cursor, or null when the pointer leaves the stack. */
  onCursor?: (index: number | null) => void
  /** Inclusive sample range, or null when the selection is cleared. */
  onSelect?: (range: { from: number; to: number } | null) => void
  /** Drives the header readout only; it never rebuilds a chart. */
  cursorIndex?: number | null
  panelHeight?: number
}

/** The Expressive line is heavier than the token's base weight; the token is still the source. */
const STROKE_EMPHASIS = 1.5

const FILL_TOP_ALPHA = 0.28

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

/**
 * µPlot treats `null` as a gap and anything else as a value, so the sentinel-free `NaN`s the
 * codec produces have to be translated. Skipping this draws a straight line through every
 * tunnel and dropout in the ride — the canvas silently ignores a `NaN` coordinate and simply
 * continues the path from the last good point.
 */
function toPlotData(values: Float64Array): (number | null)[] {
  const out = new Array<number | null>(values.length)
  for (let i = 0; i < values.length; i++) {
    const value = values[i]!
    out[i] = Number.isNaN(value) ? null : value
  }
  return out
}

export function TelemetryCharts({
  x,
  xFormat,
  panels,
  onCursor,
  onSelect,
  cursorIndex = null,
  panelHeight = 132,
}: TelemetryChartsProps) {
  const theme = useChartTheme()
  const syncKey = useId()
  const host = useRef<HTMLDivElement>(null)
  const charts = useRef<uPlot[]>([])

  // Callbacks live in refs so a parent re-rendering on every cursor move does not tear down
  // and rebuild the charts underneath the athlete's pointer.
  const cursorRef = useRef(onCursor)
  const selectRef = useRef(onSelect)
  cursorRef.current = onCursor
  selectRef.current = onSelect

  const xs = useMemo(() => Array.from(x), [x])
  const series = useMemo(() => panels.map((panel) => toPlotData(panel.values)), [panels])
  const descriptions = useMemo(
    () => Object.fromEntries(panels.map((panel) => [panel.key, describePanel(panel)])),
    [panels],
  )

  useEffect(() => {
    const container = host.current
    if (!container || panels.length === 0) return

    const sync = uPlot.sync(syncKey)
    const width = container.clientWidth || 720
    const instances: uPlot[] = []

    panels.forEach((panel, index) => {
      const target = container.querySelector<HTMLDivElement>(`[data-panel="${panel.key}"]`)
      if (!target) return

      const colour = theme.channel(panel.key)
      const isLast = index === panels.length - 1

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
          width,
          height: panelHeight,
          // Only the bottom chart carries the x labels; repeating them five times is noise.
          padding: [8, 8, isLast ? 0 : 4, 0],
          legend: { show: false },
          cursor: {
            sync: { key: sync.key, scales: ['x', null] },
            drag: { x: true, y: false, setScale: false, dist: 4 },
            // The ring is the bed, not the page: the dot sits on the tonal container the panel
            // is drawn in, and a gap ring only reads as a gap if it is that colour.
            points: { size: 9, width: 3, stroke: () => colour, fill: () => theme.bed },
          },
          scales: {
            x: { time: false },
            // Fixed to the trimmed range rather than left to µPlot's auto-fit, which scales to
            // the extremes and is what let one stopped sample flatten an entire walk.
            y: (() => {
              const bounds = axisRange(panel.values)
              return bounds ? { range: () => bounds } : {}
            })(),
          },
          axes: [
            {
              show: isLast,
              stroke: theme.inkMuted,
              font: theme.axisFont,
              grid: { stroke: theme.gridline, width: 1 },
              ticks: { stroke: theme.axis, width: 1, size: 4 },
              values: (_u, splits) => splits.map((value) => xFormat(value)),
            },
            {
              stroke: theme.inkMuted,
              font: theme.axisFont,
              // The gutter is measured in pixels, so it has to follow the reader's font size:
              // "1:23:45" at 200% text does not fit in a gutter cut for an 11px label.
              size: Math.max(52, Math.round(theme.axisLabelPx * 5)),
              grid: { stroke: theme.gridline, width: 1 },
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
              // µPlot exposes the cap but not the join; it already joins round internally, so
              // this is the whole of the round-stroke story on the web side.
              cap: 'round',
              fill: wash,
              points: { show: false },
              spanGaps: false,
            },
          ],
          hooks: {
            setCursor: [
              (u) => {
                if (index !== 0) return
                cursorRef.current?.(u.cursor.idx ?? null)
              },
            ],
            setSelect: [
              (u) => {
                if (index !== 0) return
                if (u.select.width <= 0) {
                  selectRef.current?.(null)
                  return
                }
                const from = u.posToIdx(u.select.left)
                const to = u.posToIdx(u.select.left + u.select.width)
                selectRef.current?.({ from, to })
              },
            ],
          },
        },
        [xs, series[index]!] as unknown as uPlot.AlignedData,
        target,
      )

      instances.push(chart)
    })

    charts.current = instances

    const observer = new ResizeObserver(() => {
      const next = container.clientWidth
      if (next > 0) instances.forEach((chart) => chart.setSize({ width: next, height: panelHeight }))
    })
    observer.observe(container)

    return () => {
      observer.disconnect()
      instances.forEach((chart) => chart.destroy())
      charts.current = []
    }
    // Rebuilt when the data, the panel set or the palette changes — the three things µPlot
    // cannot be told about after construction.
  }, [xs, series, panels, panelHeight, syncKey, theme, xFormat])

  return (
    <div ref={host} className="hh-chart-stack">
      {panels.map((panel) => {
        const at = cursorIndex === null ? NaN : (panel.values[cursorIndex] ?? NaN)
        return (
          <figure key={panel.key} className="hh-chart">
            <figcaption className="hh-chart__header">
              <span className="t-label-medium hh-chart__label">
                {/* The swatch is what makes this direct labelling rather than a legend:
                    the colour is next to its own name, not in a key somewhere else. */}
                <span
                  className="hh-chart__swatch"
                  style={{ background: `var(--chart-channel-${panel.key})` }}
                  aria-hidden="true"
                />
                {panel.label}
              </span>
              {/* Tinted with the channel's own colour rather than a neutral: the pill is part
                  of the direct labelling, so it carries the identity the swatch and the line
                  already carry. */}
              <span
                className="t-title-medium-emphasized numeric hh-chart__readout"
                style={{
                  background: `color-mix(in srgb, var(--chart-channel-${panel.key}) 16%, transparent)`,
                }}
              >
                {Number.isNaN(at) ? (panel.summary ?? '—') : panel.format(at)}
              </span>
            </figcaption>
            <div
              data-panel={panel.key}
              className="hh-chart__canvas"
              role="img"
              aria-label={descriptions[panel.key] ?? panel.label}
            />
          </figure>
        )
      })}
    </div>
  )
}
