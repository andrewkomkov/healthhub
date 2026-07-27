import type { LineString } from 'geojson'
import { describe, expect, it } from 'vitest'
import { padBounds, positionAt, routeFeatureCollection, routeGeometry } from './route'

const f = (values: number[]) => Float64Array.from(values)

describe('routeGeometry', () => {
  it('breaks the line where the fix was lost', () => {
    const lat = f([55.75, 55.751, NaN, NaN, 55.754, 55.755])
    const lon = f([37.61, 37.611, NaN, NaN, 37.614, 37.615])

    const geometry = routeGeometry(lat, lon)

    expect(geometry.segments).toHaveLength(2)
    expect(geometry.segments[0]).toHaveLength(2)
    expect(geometry.segments[1]).toHaveLength(2)
    // The gap is the point: one polyline here would draw straight through the tunnel.
    expect(geometry.starts).toEqual([0, 4])
  })

  it('keeps a continuous track as one segment', () => {
    const lat = f([55.75, 55.7501, 55.7502])
    const lon = f([37.61, 37.6101, 37.6102])

    expect(routeGeometry(lat, lon).segments).toHaveLength(1)
  })

  it('splits an implausible jump rather than drawing a spike across the city', () => {
    // 16 km covered in one second: a dropout the recorder papered over.
    const lat = f([55.75, 55.7501, 55.9, 55.9001])
    const lon = f([37.61, 37.6101, 37.61, 37.6101])
    const time = f([0, 1000, 2000, 3000])

    const geometry = routeGeometry(lat, lon, time)

    expect(geometry.segments).toHaveLength(2)
    expect(geometry.starts).toEqual([0, 2])
  })

  it('keeps a downsampled preview in one piece', () => {
    // A preview puts nine seconds between samples; 250 m apart is 100 km/h, not a dropout.
    const lat = f([55.75, 55.7522, 55.7544, 55.7566])
    const lon = f([37.61, 37.61, 37.61, 37.61])
    const time = f([0, 9000, 18000, 27000])

    expect(routeGeometry(lat, lon, time).segments).toHaveLength(1)
  })

  it('drops a lone fix instead of leaving a dot on the map', () => {
    const lat = f([55.75, NaN, NaN, 55.76, NaN])
    const lon = f([37.61, NaN, NaN, 37.62, NaN])

    expect(routeGeometry(lat, lon).segments).toHaveLength(0)
  })

  it('reports no geometry at all for an indoor workout', () => {
    const geometry = routeGeometry(null, null)

    expect(geometry.segments).toHaveLength(0)
    expect(geometry.bounds).toBeNull()
  })

  it('bounds the whole track, gaps included', () => {
    // Two lone fixes either side of a gap draw nothing, so there is no box either.
    expect(routeGeometry(f([55.75, NaN, 55.77]), f([37.61, NaN, 37.6])).bounds).toBeNull()

    const geometry = routeGeometry(
      f([55.75, 55.7501, NaN, 55.7503, 55.7504]),
      f([37.61, 37.6101, NaN, 37.6099, 37.6098]),
      f([0, 1000, 2000, 3000, 4000]),
    )
    expect(geometry.segments).toHaveLength(2)
    expect(geometry.bounds).toEqual([37.6098, 55.75, 37.6101, 55.7504])
  })
})

describe('routeFeatureCollection', () => {
  it('emits one feature per segment in longitude-latitude order', () => {
    const geometry = routeGeometry(f([55.75, 55.751]), f([37.61, 37.611]))
    const collection = routeFeatureCollection(geometry)

    expect(collection.features).toHaveLength(1)
    const line = collection.features[0]!.geometry as LineString
    expect(line.coordinates[0]).toEqual([37.61, 55.75])
  })
})

describe('positionAt', () => {
  it('has no position for a sample that had no fix', () => {
    const lat = f([55.75, NaN])
    const lon = f([37.61, NaN])

    expect(positionAt(lat, lon, 0)).toEqual([37.61, 55.75])
    expect(positionAt(lat, lon, 1)).toBeNull()
    expect(positionAt(lat, lon, 9)).toBeNull()
  })
})

describe('padBounds', () => {
  it('gives a single-point activity a viewport it can be seen in', () => {
    const [west, south, east, north] = padBounds([37.61, 55.75, 37.61, 55.75])

    expect(east - west).toBeGreaterThan(0)
    expect(north - south).toBeGreaterThan(0)
  })
})
