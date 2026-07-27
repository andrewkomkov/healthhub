import { useEffect, useId, useMemo, useRef } from 'react'
import uPlot from 'uplot'
import 'uplot/dist/uPlot.min.css'
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
            points: { size: 7, width: 2, stroke: () => colour, fill: () => theme.surface },
          },
          scales: { x: { time: false } },
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
              width: theme.lineWidth,
              // A fill under every panel would stack five washes of colour on one screen;
              // the line carries the shape and the colour carries the identity.
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
              <span className="t-title-medium numeric">
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
