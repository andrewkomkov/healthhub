import type { Feature, FeatureCollection } from 'geojson'

/**
 * Geometry for the route layer.
 *
 * Kept apart from the MapLibre wrapper so the rule that matters — a gap in the fix is a gap
 * in the line, never a straight edge across a tunnel — is testable without a WebGL context.
 */

export type Position = [lon: number, lat: number]

export interface RouteGeometry {
  /** One line string per continuous run of fixes. Two segments means one gap between them. */
  segments: Position[][]
  /** `[west, south, east, north]`, or null when nothing was recorded. */
  bounds: [number, number, number, number] | null
  /** Sample index of the first fix in each segment, for mapping a chart cursor onto the line. */
  starts: number[]
}

/**
 * A leg implying this speed is a dropout the recorder papered over, not movement. Drawing it
 * produces the long straight spike across a city that makes a route look corrupt.
 *
 * The test is implied speed rather than raw distance, because sampling rates vary by two
 * orders of magnitude: a preview downsampled to 2,000 points puts hundreds of legitimate
 * metres between samples, and a fixed distance threshold would shred it into fragments.
 */
const MAX_PLAUSIBLE_SPEED_MPS = 60

/** Used only when the object carries no time channel to reason with. */
const MAX_PLAUSIBLE_JUMP_M = 2_000

const EARTH_RADIUS_M = 6_371_008.8

function metresBetween(a: Position, b: Position): number {
  const toRad = Math.PI / 180
  const dLat = (b[1] - a[1]) * toRad
  const dLon = (b[0] - a[0]) * toRad
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(a[1] * toRad) * Math.cos(b[1] * toRad) * Math.sin(dLon / 2) ** 2
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))))
}

export function routeGeometry(
  lat: Float64Array | null,
  lon: Float64Array | null,
  time: Float64Array | null = null,
): RouteGeometry {
  const empty: RouteGeometry = { segments: [], bounds: null, starts: [] }
  if (!lat || !lon) return empty

  const segments: Position[][] = []
  const starts: number[] = []
  let current: Position[] = []
  let currentStart = 0

  let west = Infinity
  let south = Infinity
  let east = -Infinity
  let north = -Infinity
  let previousIndex = -1

  const closeSegment = () => {
    // A single orphaned fix draws nothing; keeping it would put a stray dot on the map.
    if (current.length > 1) {
      segments.push(current)
      starts.push(currentStart)
    }
    current = []
  }

  for (let i = 0; i < lat.length; i++) {
    const y = lat[i]!
    const x = lon[i]!
    if (Number.isNaN(y) || Number.isNaN(x)) {
      closeSegment()
      continue
    }

    const point: Position = [x, y]
    const previous = current[current.length - 1]
    if (previous && implausible(previous, point, previousIndex, i, time)) closeSegment()

    if (current.length === 0) currentStart = i
    current.push(point)
    previousIndex = i

    if (x < west) west = x
    if (x > east) east = x
    if (y < south) south = y
    if (y > north) north = y
  }
  closeSegment()

  if (segments.length === 0) return empty
  return { segments, bounds: [west, south, east, north], starts }
}

function implausible(
  from: Position,
  to: Position,
  fromIndex: number,
  toIndex: number,
  time: Float64Array | null,
): boolean {
  const metres = metresBetween(from, to)
  if (!time) return metres > MAX_PLAUSIBLE_JUMP_M

  const seconds = (time[toIndex]! - time[fromIndex]!) / 1000
  if (!Number.isFinite(seconds) || seconds <= 0) return metres > MAX_PLAUSIBLE_JUMP_M
  return metres / seconds > MAX_PLAUSIBLE_SPEED_MPS
}

export function routeFeatureCollection(geometry: RouteGeometry): FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: geometry.segments.map(
      (coordinates, index): Feature => ({
        type: 'Feature',
        properties: { segment: index },
        geometry: { type: 'LineString', coordinates },
      }),
    ),
  }
}

/** Where a given sample sits on the drawn line, or null if that sample had no fix. */
export function positionAt(
  lat: Float64Array | null,
  lon: Float64Array | null,
  index: number,
): Position | null {
  if (!lat || !lon || index < 0 || index >= lat.length) return null
  const y = lat[index]!
  const x = lon[index]!
  if (Number.isNaN(y) || Number.isNaN(x)) return null
  return [x, y]
}

/** Pads a bounding box so a route never touches the edge of the viewport. */
export function padBounds(
  bounds: [number, number, number, number],
  fraction = 0.08,
): [number, number, number, number] {
  const [west, south, east, north] = bounds
  // A point activity has zero span; without a floor the map would zoom to infinity.
  const spanX = Math.max(east - west, 0.0005)
  const spanY = Math.max(north - south, 0.0005)
  return [
    west - spanX * fraction,
    south - spanY * fraction,
    east + spanX * fraction,
    north + spanY * fraction,
  ]
}
