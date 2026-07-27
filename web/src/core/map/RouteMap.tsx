import { useEffect, useMemo, useRef } from 'react'
import {
  Map as MapLibreMap,
  NavigationControl,
  setWorkerUrl,
  type GeoJSONSource,
  type StyleSpecification,
} from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'
import type { Feature, FeatureCollection } from 'geojson'
import 'maplibre-gl/dist/maplibre-gl.css'
import { useChartTheme } from '../charts/theme'
import { padBounds, routeFeatureCollection, routeGeometry, type Position } from './route'

/**
 * The route, drawn by MapLibre under a style built from the design tokens.
 *
 * There is no map account to sign up for and no key to configure, which is a deliberate
 * consequence of the constitution's "clone and run" constraint: the default style is the
 * product's own surface colour with the route on top, and a basemap appears only if the
 * deployment points `VITE_MAP_TILES_URL` at a tile source it is entitled to use. A route
 * without a basemap still answers the question the athlete is asking — what shape was this
 * ride, and where on it was I going fast.
 *
 * Gaps in the fix are gaps in the line. That is `routeGeometry`'s job, and it is tested
 * without a WebGL context precisely so this file can stay thin.
 */

/**
 * MapLibre parses geometry in a web worker, and resolves that worker's script relative to its
 * own module URL — which after bundling is an asset path that does not exist. The request
 * fails, no error surfaces anywhere, and the map renders as an empty rectangle with working
 * zoom buttons. Pointing it at the URL the bundler emitted is the whole fix.
 */
setWorkerUrl(workerUrl)

const TILES_URL = import.meta.env['VITE_MAP_TILES_URL'] as string | undefined
const TILES_ATTRIBUTION = import.meta.env['VITE_MAP_TILES_ATTRIBUTION'] as string | undefined

const ROUTE_SOURCE = 'route'
const MARKER_SOURCE = 'cursor'
const ENDS_SOURCE = 'ends'

export interface RouteMapProps {
  lat: Float64Array | null
  lon: Float64Array | null
  time: Float64Array | null
  /** Sample the chart cursor is over; the marker follows it. */
  cursorIndex: number | null
  height?: number
}

function pointFeature(position: Position | null): FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: position
      ? [{ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: position } }]
      : [],
  }
}

export function RouteMap({ lat, lon, time, cursorIndex, height = 320 }: RouteMapProps) {
  const theme = useChartTheme()
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<MapLibreMap | null>(null)
  const ready = useRef(false)

  const geometry = useMemo(() => routeGeometry(lat, lon, time), [lat, lon, time])

  useEffect(() => {
    const node = container.current
    if (!node || geometry.bounds === null) return

    const style: StyleSpecification = {
      version: 8,
      sources: TILES_URL
        ? {
            basemap: {
              type: 'raster',
              tiles: [TILES_URL],
              tileSize: 256,
              attribution: TILES_ATTRIBUTION ?? '',
            },
          }
        : {},
      layers: [
        { id: 'background', type: 'background', paint: { 'background-color': theme.surface } },
        ...(TILES_URL
          ? [
              {
                id: 'basemap',
                type: 'raster' as const,
                source: 'basemap',
                // Held back from full opacity so the route stays the brightest thing on it.
                paint: { 'raster-opacity': 0.85, 'raster-saturation': -0.3 },
              },
            ]
          : []),
      ],
    }

    const instance = new MapLibreMap({
      container: node,
      style,
      bounds: padBounds(geometry.bounds),
      dragRotate: false,
      pitchWithRotate: false,
      touchZoomRotate: true,
      attributionControl: TILES_URL ? undefined : false,
    })
    instance.touchZoomRotate.disableRotation()
    instance.addControl(new NavigationControl({ showCompass: false }), 'top-right')

    instance.on('load', () => {
      instance.addSource(ROUTE_SOURCE, {
        type: 'geojson',
        data: routeFeatureCollection(geometry),
      })
      instance.addSource(ENDS_SOURCE, {
        type: 'geojson',
        data: {
          type: 'FeatureCollection',
          features: endpoints(geometry.segments),
        },
      })
      instance.addSource(MARKER_SOURCE, { type: 'geojson', data: pointFeature(null) })

      // A casing under the line keeps the route legible over both a pale basemap and a dark
      // one — the same trick the token layer uses for text on unpredictable surfaces.
      instance.addLayer({
        id: 'route-casing',
        type: 'line',
        source: ROUTE_SOURCE,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: {
          'line-color': theme.surface,
          'line-width': theme.lineWidth * 2.5,
          'line-opacity': 0.7,
        },
      })
      instance.addLayer({
        id: 'route',
        type: 'line',
        source: ROUTE_SOURCE,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': theme.channel('speed'), 'line-width': theme.lineWidth * 1.5 },
      })
      instance.addLayer({
        id: 'route-ends',
        type: 'circle',
        source: ENDS_SOURCE,
        paint: {
          'circle-radius': 5,
          'circle-color': [
            'case',
            ['==', ['get', 'kind'], 'start'],
            theme.channel('elevation'),
            theme.channel('hr'),
          ],
          'circle-stroke-width': 2,
          'circle-stroke-color': theme.surface,
        },
      })
      instance.addLayer({
        id: 'cursor',
        type: 'circle',
        source: MARKER_SOURCE,
        paint: {
          'circle-radius': 7,
          'circle-color': theme.channel('speed'),
          'circle-stroke-width': 3,
          'circle-stroke-color': theme.surface,
        },
      })

      ready.current = true
    })

    map.current = instance
    // The only way to ask a WebGL canvas what it actually drew is to hold the instance, so
    // development builds expose it. This is how "is the route on the map" gets answered by a
    // test rather than by a human squinting at a screenshot.
    if (import.meta.env.DEV) (window as unknown as Record<string, unknown>)['__hhMap'] = instance
    return () => {
      ready.current = false
      map.current = null
      instance.remove()
    }
  }, [geometry, theme])

  // The marker is pushed straight at the source rather than through React state: the cursor
  // moves with the pointer, and a re-render per mouse move would be felt.
  useEffect(() => {
    const instance = map.current
    if (!instance || !ready.current) return

    const source = instance.getSource(MARKER_SOURCE) as GeoJSONSource | undefined
    if (!source) return

    const position =
      cursorIndex === null ? null : positionOf(lat, lon, cursorIndex, geometry.segments)
    source.setData(pointFeature(position))
  }, [cursorIndex, lat, lon, geometry])

  if (geometry.bounds === null) return null

  // Not `role="img"`: that makes the whole subtree presentational, and the subtree contains
  // MapLibre's own zoom buttons and its keyboard-pannable canvas. A labelled region keeps the
  // name and leaves the controls reachable.
  return (
    <div
      ref={container}
      className="hh-map"
      style={{ height }}
      role="region"
      aria-label={`Route map, ${summary(geometry.segments)}`}
    />
  )
}

/**
 * The marker position for a sample.
 *
 * A sample inside a gap has no position of its own; rather than hiding the marker — which
 * reads as a broken cursor — it holds at the last fix before the gap, which is where the
 * athlete actually was when the signal went.
 */
function positionOf(
  lat: Float64Array | null,
  lon: Float64Array | null,
  index: number,
  segments: Position[][],
): Position | null {
  if (!lat || !lon || segments.length === 0) return null
  for (let i = Math.min(index, lat.length - 1); i >= 0; i--) {
    const y = lat[i]!
    const x = lon[i]!
    if (!Number.isNaN(y) && !Number.isNaN(x)) return [x, y]
  }
  return null
}

/**
 * The one fact about the drawn route that can be spoken. A gap is the difference between "the
 * ride was here" and "the receiver lost the sky for six minutes", and it is visible to
 * everybody else as a break in the line.
 */
function summary(segments: Position[][]): string {
  if (segments.length === 0) return 'no positions'
  if (segments.length === 1) return 'one continuous track'
  return `${segments.length} track segments, split by gaps in the fix`
}

function endpoints(segments: Position[][]): Feature[] {
  const first = segments[0]?.[0]
  const lastSegment = segments[segments.length - 1]
  const last = lastSegment?.[lastSegment.length - 1]
  const features: Feature[] = []
  if (first) {
    features.push({
      type: 'Feature',
      properties: { kind: 'start' },
      geometry: { type: 'Point', coordinates: first },
    })
  }
  if (last) {
    features.push({
      type: 'Feature',
      properties: { kind: 'finish' },
      geometry: { type: 'Point', coordinates: last },
    })
  }
  return features
}
