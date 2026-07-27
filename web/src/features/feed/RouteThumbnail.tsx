import { useEffect, useRef } from 'react'
import { decodePolyline } from '../../core/polyline'

/**
 * A route preview drawn from the activity's encoded polyline.
 *
 * Canvas rather than an image request: the geometry is already on the row, so a card costs
 * nothing extra to render and the feed stays a single D1 query (research.md R-005).
 *
 * The stroke uses the speed channel's series colour so a route reads as the same entity here
 * as it does on the detail screen's charts.
 */
export function RouteThumbnail({
  polyline,
  height = 120,
}: {
  polyline: string
  height?: number
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const points = decodePolyline(polyline)
    if (points.length < 2) return

    const dpr = window.devicePixelRatio || 1
    const width = canvas.clientWidth
    canvas.width = width * dpr
    canvas.height = height * dpr

    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.scale(dpr, dpr)
    ctx.clearRect(0, 0, width, height)

    let minLat = Infinity
    let maxLat = -Infinity
    let minLon = Infinity
    let maxLon = -Infinity
    for (const [lat, lon] of points) {
      if (lat < minLat) minLat = lat
      if (lat > maxLat) maxLat = lat
      if (lon < minLon) minLon = lon
      if (lon > maxLon) maxLon = lon
    }

    // Preserve aspect ratio: a squashed route is a misleading route.
    const pad = 10
    const spanLat = Math.max(maxLat - minLat, 1e-6)
    const spanLon = Math.max(maxLon - minLon, 1e-6)
    const scale = Math.min((width - pad * 2) / spanLon, (height - pad * 2) / spanLat)
    const offsetX = (width - spanLon * scale) / 2
    const offsetY = (height - spanLat * scale) / 2

    const styles = getComputedStyle(document.documentElement)
    ctx.strokeStyle = styles.getPropertyValue('--chart-series-1').trim() || '#2a78d6'
    ctx.lineWidth = 2
    ctx.lineJoin = 'round'
    ctx.lineCap = 'round'

    ctx.beginPath()
    points.forEach(([lat, lon], i) => {
      const x = offsetX + (lon - minLon) * scale
      // Latitude increases northward; canvas y increases downward.
      const y = offsetY + (maxLat - lat) * scale
      if (i === 0) ctx.moveTo(x, y)
      else ctx.lineTo(x, y)
    })
    ctx.stroke()
  }, [polyline, height])

  return (
    <canvas
      ref={canvasRef}
      style={{ width: '100%', height, display: 'block' }}
      aria-label="Route preview"
      role="img"
    />
  )
}
