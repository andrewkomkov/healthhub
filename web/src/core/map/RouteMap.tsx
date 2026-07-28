import { useEffect, useRef } from 'react'
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
import { padBounds, routeFeatureCollection, type Position, type RouteGeometry } from './route'

/**
 * The route, drawn by MapLibre over an openly licensed basemap.
 *
 * There is still no map account to sign up for and no key to configure — the constitution's
 * "clone and run" constraint rules those out — but "no key" no longer means "no basemap". The
 * default is OpenFreeMap: OpenStreetMap data, rendered through the OpenMapTiles schema, served
 * from a public instance that asks for no registration and sets no limits, and self-hostable by
 * anyone who outgrows it. That is R-009's "openly licensed vector tile source" arriving four
 * sessions late. `VITE_MAP_TILES_URL` still overrides it, and `none` still turns it off for a
 * deployment that must not talk to a third party at all.
 *
 * Two things follow from the basemap being remote rather than a colour we own:
 *
 *  - **The route does not depend on it.** If the style cannot be fetched — offline, blocked,
 *    or simply slow — the map falls back to the product's own surface colour and draws the
 *    ride on that. A route without a basemap still answers what the athlete is asking: what
 *    shape was this ride, and where on it was I going fast.
 *  - **It is knocked back before the route goes on it.** A full-strength basemap competes with
 *    the line that is the actual subject; a scrim of the product surface over it does what
 *    `raster-opacity` does for the raster path.
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

/**
 * The two default styles, one per palette. Both are MapLibre style documents that name their
 * own sources, glyphs and sprites, so nothing here has to know the tile URL — and both carry
 * their attribution in the TileJSON they point at, which is how the control below gets its
 * credit line without this file writing one.
 */
const OPENFREEMAP = {
  light: 'https://tiles.openfreemap.org/styles/positron',
  dark: 'https://tiles.openfreemap.org/styles/dark',
}

const CONFIGURED_TILES = (
  (import.meta.env['VITE_MAP_TILES_URL'] as string | undefined) ?? ''
).trim()
const TILES_ATTRIBUTION = import.meta.env['VITE_MAP_TILES_ATTRIBUTION'] as string | undefined

/** How far the basemap is knocked back towards the product surface before the route goes on. */
const SCRIM_ALPHA = 0.22

/**
 * How long a style may take before the route is drawn without it.
 *
 * A failed fetch raises `error` and is handled the moment it happens; a *hung* one raises
 * nothing at all, and the athlete is left looking at an empty rectangle wondering whether the
 * ride recorded. Long enough not to fire on a slow train connection, short enough that nobody
 * concludes the map is broken.
 */
const STYLE_TIMEOUT_MS = 6000

const ROUTE_SOURCE = 'route'
const MARKER_SOURCE = 'cursor'
const ENDS_SOURCE = 'ends'
const SCRIM_LAYER = 'basemap-scrim'

type Basemap =
  /** A whole MapLibre style document, fetched by the renderer. Vector, and the default. */
  | { kind: 'style'; url: string }
  /** A `{z}/{x}/{y}` template, wrapped in a style of ours. What the option used to mean. */
  | { kind: 'raster'; url: string }
  | null

/**
 * Which basemap this deployment gets.
 *
 * One option covers both shapes because a deployment configuring a tile source knows which one
 * it has, and `{z}` tells the two apart with no ambiguity: a style document never contains it,
 * and a tile template always does.
 */
function basemapFor(dark: boolean): Basemap {
  if (CONFIGURED_TILES.toLowerCase() === 'none') return null
  if (CONFIGURED_TILES === '')
    return { kind: 'style', url: dark ? OPENFREEMAP.dark : OPENFREEMAP.light }
  if (CONFIGURED_TILES.includes('{z}')) return { kind: 'raster', url: CONFIGURED_TILES }
  return { kind: 'style', url: CONFIGURED_TILES }
}

/**
 * The style this file owns: the product's surface colour, and a raster basemap on it if one is
 * configured. Also the fallback when a remote style does not arrive, which is why it takes no
 * network of its own.
 */
function localStyle(surface: string, raster: Basemap): StyleSpecification {
  const tiles = raster?.kind === 'raster' ? raster.url : null
  return {
    version: 8,
    sources: tiles
      ? {
          basemap: {
            type: 'raster',
            tiles: [tiles],
            tileSize: 256,
            attribution: TILES_ATTRIBUTION ?? '',
          },
        }
      : {},
    layers: [
      { id: 'background', type: 'background', paint: { 'background-color': surface } },
      ...(tiles
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
}

export interface RouteMapProps {
  /**
   * The segmented track. Computed by the caller rather than here, because "is there anything to
   * draw" is a question the *screen* has to answer too — it is what decides between this map and
   * the explanation that stands in for it — and answering it in two places is how a recording
   * with a single stored fix ended up rendering neither.
   */
  geometry: RouteGeometry
  lat: Float64Array | null
  lon: Float64Array | null
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

export function RouteMap({ geometry, lat, lon, cursorIndex, height = 320 }: RouteMapProps) {
  const theme = useChartTheme()
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<MapLibreMap | null>(null)
  const ready = useRef(false)

  useEffect(() => {
    const node = container.current
    if (!node || geometry.bounds === null) return

    const basemap = basemapFor(theme.isDark)
    const remote = basemap?.kind === 'style' ? basemap.url : null

    const instance = new MapLibreMap({
      container: node,
      style: remote ?? localStyle(theme.surface, basemap),
      bounds: padBounds(geometry.bounds),
      dragRotate: false,
      pitchWithRotate: false,
      touchZoomRotate: true,
      attributionControl: basemap ? undefined : false,
    })
    instance.touchZoomRotate.disableRotation()
    instance.addControl(new NavigationControl({ showCompass: false }), 'top-right')

    /*
     * Drop the remote style and draw the ride on the product's own surface instead.
     *
     * Called from two places — an `error` before the style arrived, and the timeout — and it has
     * to be safe from both, hence the guard. `diff: false` because there is nothing to diff
     * against: whatever the failed style left behind is not a style this one is a change to.
     */
    let settled = false
    let onBasemap = remote !== null
    const withoutBasemap = () => {
      if (settled) return
      settled = true
      onBasemap = false
      instance.setStyle(localStyle(theme.surface, null), { diff: false })
    }

    const timer = window.setTimeout(withoutBasemap, STYLE_TIMEOUT_MS)
    // Tile and glyph failures raise this too, and they are not fatal — the guard is what tells
    // "the style never came" apart from "one tile in the corner did not".
    instance.on('error', withoutBasemap)

    instance.on('load', () => {
      window.clearTimeout(timer)
      settled = true

      // Over the vendor's own layers, under everything of ours: the basemap is context for the
      // route, not the subject. A background layer covers the viewport wherever it sits in the
      // order, which is what makes this a scrim rather than a fill needing geometry.
      if (onBasemap) {
        instance.addLayer({
          id: SCRIM_LAYER,
          type: 'background',
          paint: { 'background-color': theme.surface, 'background-opacity': SCRIM_ALPHA },
        })
      }

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
      window.clearTimeout(timer)
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
