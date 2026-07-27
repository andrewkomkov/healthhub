/**
 * Google encoded-polyline decoder.
 *
 * The feed stores a simplified route as an encoded polyline on the activity row, which is why
 * a feed card can draw a route thumbnail without touching R2 at all (research.md R-005).
 *
 * The Kotlin encoder in core:telemetry is validated against the same test vector as this
 * decoder, so the two implementations cannot drift.
 */
export function decodePolyline(encoded: string, precision = 5): [number, number][] {
  const factor = 10 ** precision
  const points: [number, number][] = []
  let index = 0
  let lat = 0
  let lon = 0

  while (index < encoded.length) {
    let result = 0
    let shift = 0
    let byte: number

    do {
      byte = encoded.charCodeAt(index++) - 63
      result |= (byte & 0x1f) << shift
      shift += 5
    } while (byte >= 0x20)
    lat += result & 1 ? ~(result >> 1) : result >> 1

    result = 0
    shift = 0
    do {
      byte = encoded.charCodeAt(index++) - 63
      result |= (byte & 0x1f) << shift
      shift += 5
    } while (byte >= 0x20)
    lon += result & 1 ? ~(result >> 1) : result >> 1

    points.push([lat / factor, lon / factor])
  }

  return points
}
