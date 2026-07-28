/**
 * The icon set, drawn here rather than imported.
 *
 * The phone draws its metrics with Material Symbols — a stopwatch beside the moving time, a heart
 * beside the average — because a glyph survives being glanced at and a word has to be read. The
 * browser has to say the same things with the same marks, and shipping an icon font or a symbol
 * package for eleven glyphs is 300 kB and a network request against a bundle that is 200 kB in
 * total (SC-010's budget, see AGENT-NOTES).
 *
 * So they are eleven paths on a 24-unit grid, stroked in `currentColor` at a single weight. Not a
 * Material Symbols tracing — that font is licensed and these are drawn to match its *proportions*
 * (24 grid, 2 px stroke, round caps), which is what makes the two clients read as one product
 * rather than as two icon styles.
 *
 * Filled where the real mark is filled — a heart and a flame are solids — and stroked where it is
 * a diagram.
 */

export type IconName =
  | 'timer'
  | 'schedule'
  | 'speed'
  | 'heart'
  | 'pulse'
  | 'terrain'
  | 'bolt'
  | 'flame'
  | 'route'
  | 'layers'
  | 'map'

interface Glyph {
  /** Stroked outline. */
  d?: string
  /** Filled body, for the marks that are solids. */
  fill?: string
}

const GLYPHS: Record<IconName, Glyph> = {
  timer: {
    d: 'M9 2.75h6M12 7.25a7.25 7.25 0 1 1 0 14.5 7.25 7.25 0 0 1 0-14.5ZM12 11v3.5l2.25 2.25',
  },
  schedule: { d: 'M12 3.75a8.25 8.25 0 1 1 0 16.5 8.25 8.25 0 0 1 0-16.5ZM12 7.5V12l3 2' },
  speed: { d: 'M3.75 18a8.25 8.25 0 1 1 16.5 0M12 18l4.5-6.5' },
  heart: {
    fill: 'M12 20.6 4.9 13.4a4.7 4.7 0 0 1 .3-6.9 4.7 4.7 0 0 1 6.8.7 4.7 4.7 0 0 1 6.8-.7 4.7 4.7 0 0 1 .3 6.9Z',
  },
  pulse: { d: 'M2.75 12h4L9 6.75 12.5 17.5 15 12h6.25' },
  terrain: { d: 'M2.5 19h19L14.2 7.5 10.6 13.4 8.4 10Z' },
  bolt: { fill: 'M13.4 2.5 5.5 13.8h4.6L9 21.5l8.2-11.6h-4.7Z' },
  flame: {
    fill: 'M12 2.6c2.3 3 5.2 4.8 5.2 8.6a5.2 5.2 0 0 1-10.4 0c0-1.8.8-3 1.8-4 .2 1.1.9 2 1.8 2.4-.6-2.4-.2-5 1.6-7Z',
  },
  route: { d: 'M6.5 19.5a2.5 2.5 0 1 0 0-5h11a2.5 2.5 0 1 0 0-5' },
  layers: { d: 'm12 3.2 8.5 4.6L12 12.4 3.5 7.8ZM3.5 14.2 12 18.8l8.5-4.6' },
  map: { d: 'm9 4.6-6 2.1v13.2l6-2.1 6 2.1 6-2.1V4.5l-6 2.1ZM9 4.6v13.2M15 6.7v13.2' },
}

export function Icon({
  name,
  size = 16,
  className,
}: {
  name: IconName
  size?: number
  className?: string
}) {
  const glyph = GLYPHS[name]
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      // Decorative in every use so far: the figure beside it is the label. A caller that needs
      // it to speak wraps it in something that does.
      aria-hidden="true"
      focusable="false"
    >
      {glyph.fill && <path d={glyph.fill} fill="currentColor" />}
      {glyph.d && (
        <path
          d={glyph.d}
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      )}
    </svg>
  )
}
