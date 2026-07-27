import { useEffect, useState } from 'react'

/**
 * Chart chrome read from the generated token sheet.
 *
 * µPlot ships its own greys and blues; none of them appear in this product. Everything the
 * renderer needs is resolved from CSS custom properties here, which is how Principle III
 * ("one token source") survives contact with a canvas library that knows nothing about it.
 *
 * Colours are read rather than imported because they change at runtime: the OS colour scheme,
 * the explicit theme toggle, and the Material You palette pushed from the athlete's phone all
 * land as CSS. A canvas cannot inherit any of that, so it has to be told.
 */
export interface ChartTheme {
  surface: string
  /**
   * The tonal container a panel is drawn on, two steps above the card.
   *
   * `surface` is the page; this is the bed. The Expressive treatment puts every plot inside a
   * shaped container so the series sits *in* something, and the canvas has to know that colour
   * to draw a gap ring around the cursor dot that matches what CSS painted underneath it.
   */
  bed: string
  inkPrimary: string
  inkSecondary: string
  inkMuted: string
  gridline: string
  axis: string
  lineWidth: number
  /** A CSS `font` shorthand for axis labels, so canvas text matches the type scale. */
  axisFont: string
  /**
   * The resolved axis label size in CSS pixels. µPlot sizes its gutters in pixels, so the
   * space reserved for tick labels has to be recomputed when the reader's font size changes
   * — otherwise "1:23:45" is clipped by an axis measured for the default 11px.
   */
  axisLabelPx: number
  /** Bumped whenever the palette underneath changes, so charts know to repaint. */
  version: number
  /** The colour a named channel always wears, whatever else is on screen. */
  channel(name: string): string
  /**
   * A step on the sequential ramp for the `index`-th of `count` ordered categories — heart-rate
   * zones, say. Confined to the span of the ramp that stays legible on the current surface.
   */
  ordinal(index: number, count: number): string
}

function read(styles: CSSStyleDeclaration, property: string, fallback: string): string {
  return styles.getPropertyValue(property).trim() || fallback
}

/**
 * A length token in CSS pixels.
 *
 * Type sizes are published in `rem` so they follow the reader's own font-size setting, and a
 * custom property is returned verbatim rather than resolved — `getComputedStyle` hands back
 * the string "0.6875rem", not a used value. A canvas cannot be handed that: it needs a number,
 * measured against the root font size at the moment it is asked.
 */
export function toPixels(value: string, fallbackPx: number): number {
  const amount = Number.parseFloat(value)
  if (!Number.isFinite(amount)) return fallbackPx
  if (!value.trim().endsWith('rem')) return amount
  const root = Number.parseFloat(getComputedStyle(document.documentElement).fontSize)
  return amount * (Number.isFinite(root) && root > 0 ? root : 16)
}

function snapshot(version: number): ChartTheme {
  const styles = getComputedStyle(document.documentElement)
  const lineWidth = Number.parseFloat(read(styles, '--chart-mark-line-width', '2'))
  const fontFamily = read(styles, '--md-sys-type-font-family', 'system-ui, sans-serif')
  const labelPx = toPixels(read(styles, '--md-sys-type-label-small-size', '0.6875rem'), 11)

  return {
    surface: read(styles, '--chart-surface', '#FFF8F5'),
    bed: read(styles, '--md-sys-color-surface-container-highest', '#F1DFD6'),
    inkPrimary: read(styles, '--chart-ink-primary', '#211A15'),
    inkSecondary: read(styles, '--chart-ink-secondary', '#52443A'),
    inkMuted: read(styles, '--chart-ink-muted', '#857269'),
    gridline: read(styles, '--chart-gridline', '#EADACF'),
    axis: read(styles, '--chart-axis', '#D7C2B8'),
    lineWidth: Number.isFinite(lineWidth) ? lineWidth : 2,
    axisFont: `${labelPx}px ${fontFamily}`,
    axisLabelPx: labelPx,
    version,
    channel: (name) =>
      read(styles, `--chart-channel-${name}`, read(styles, '--chart-series-1', '#2a78d6')),
    ordinal: (index, count) => {
      const floor = Number.parseInt(read(styles, '--chart-sequential-floor', '1'), 10)
      const ceiling = Number.parseInt(read(styles, '--chart-sequential-ceiling', '13'), 10)
      const step =
        count <= 1 ? ceiling : Math.round(floor + ((ceiling - floor) * index) / (count - 1))
      return read(styles, `--chart-sequential-${step}`, read(styles, '--chart-series-1', '#2a78d6'))
    },
  }
}

/**
 * The current chart palette, refreshed when anything that could change it changes.
 *
 * The three triggers are the OS switching to dark, the athlete flipping the in-app toggle
 * (a `data-theme` attribute), and the dynamic Material You sheet being injected into the head
 * after the palette arrives from the Worker. Missing that last one leaves a chart wearing the
 * default palette on a personalised page — subtle, and exactly the kind of thing nobody
 * notices until a screenshot ends up side by side with the phone.
 */
export function useChartTheme(): ChartTheme {
  const [theme, setTheme] = useState<ChartTheme>(() => snapshot(0))

  useEffect(() => {
    let version = 0
    const refresh = () => setTheme(snapshot(++version))

    const media = window.matchMedia('(prefers-color-scheme: dark)')
    media.addEventListener('change', refresh)

    const attributes = new MutationObserver(refresh)
    attributes.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme'],
    })

    const head = new MutationObserver((records) => {
      if (records.some((record) => record.addedNodes.length || record.removedNodes.length)) {
        refresh()
      }
    })
    head.observe(document.head, { childList: true })

    return () => {
      media.removeEventListener('change', refresh)
      attributes.disconnect()
      head.disconnect()
    }
  }, [])

  return theme
}
