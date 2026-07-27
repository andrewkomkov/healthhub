import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { gzipSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { decompressIfNeeded, HhtFormatError, readHht, U16_NONE } from './hht'

/**
 * The golden fixture is written by `HhtGoldenFixtureTest` in `core:telemetry` and asserted
 * here against the same expected values. The codec exists twice on purpose; this file is the
 * reason the two cannot drift apart quietly.
 *
 * If this test fails after an Android change, the format moved — fix the fixture and both
 * readers together, and bump the magic if the change is not backwards compatible.
 */
function golden(): ArrayBuffer {
  // Walked up rather than resolved from `import.meta.url`: under jsdom that is an http URL,
  // and the working directory depends on which workspace invoked vitest.
  const relative = join('fixtures', 'hht', 'golden-v1.hht')
  let directory = process.cwd()
  while (!existsSync(join(directory, relative))) {
    const parent = dirname(directory)
    if (parent === directory) throw new Error(`Could not find ${relative} above ${process.cwd()}`)
    directory = parent
  }
  const buffer = readFileSync(join(directory, relative))
  return buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength)
}

describe('the golden fixture', () => {
  it('reads the header the Kotlin writer produced', () => {
    const hht = readHht(golden())

    expect(hht.header.version).toBe(1)
    expect(hht.header.activityId).toBe('golden-v1')
    expect(hht.header.startTime).toBe(1_753_600_000_000)
    expect(hht.count).toBe(12)
    expect(hht.channelNames).toEqual([
      't',
      'lat',
      'lon',
      'elevation',
      'hr',
      'speed',
      'cadence',
      'power',
    ])
  })

  it('decodes every channel to the values the Kotlin test asserts', () => {
    const hht = readHht(golden())

    expect(hht.values('t')![11]).toBe(11_000)
    expect(hht.values('lat')![0]).toBeCloseTo(55.75, 9)
    expect(hht.values('lon')![11]).toBeCloseTo(37.6111, 9)
    expect(hht.values('speed')![2]).toBeCloseTo(5.8, 5)
    expect(hht.values('elevation')![2]).toBeCloseTo(146.25, 4)
  })

  it('treats a missing sample as unknown rather than as zero', () => {
    const hht = readHht(golden())

    // The tunnel: two samples with no fix at all.
    const lat = hht.values('lat')!
    expect(Number.isNaN(lat[5]!)).toBe(true)
    expect(Number.isNaN(lat[6]!)).toBe(true)
    expect(Number.isNaN(lat[7]!)).toBe(false)

    // A dropped heart-rate reading. Zero here would render as a dead athlete.
    const hr = hht.values('hr')!
    expect(hr[0]).toBe(118)
    expect(Number.isNaN(hr[3]!)).toBe(true)

    // The power meter stops reporting before the session ends.
    const power = hht.values('power')!
    expect(power[0]).toBe(210)
    expect(Number.isNaN(power[11]!)).toBe(true)

    // The raw view still carries the sentinel — that is what makes it raw.
    expect((hht.raw('hr') as Uint16Array)[3]).toBe(U16_NONE)
  })

  it('reads an absent channel as absent, not as a channel full of zeroes', () => {
    const hht = readHht(golden())

    expect(hht.has('temperature')).toBe(false)
    expect(hht.values('temperature')).toBeNull()
  })

  it('views the buffer instead of copying it when the payload is aligned', () => {
    const hht = readHht(golden())
    // Twelve samples: every channel lands on its natural alignment.
    for (const name of hht.channelNames) {
      hht.raw(name)
      expect(hht.channel(name)!.copied).toBe(false)
    }
  })
})

/** Builds an object in the same layout as the Kotlin writer, for cases the fixture cannot cover. */
function encode(count: number, channels: { name: string; type: string; values: number[] }[]) {
  const bytes: Record<string, number> = { u16: 2, u32: 4, f32: 4, f64: 8 }
  const header = JSON.stringify({
    v: 1,
    activityId: 'synthetic',
    startTime: 0,
    count,
    channels: channels.map((c) => ({ name: c.name, type: c.type, unit: '', scale: 1 })),
  })
  const headerBytes = new TextEncoder().encode(header)
  const unpadded = 8 + headerBytes.length
  const padding = unpadded % 8 === 0 ? 0 : 8 - (unpadded % 8)
  const payload = channels.reduce((sum, c) => sum + bytes[c.type]! * count, 0)

  const buffer = new ArrayBuffer(unpadded + padding + payload)
  const view = new DataView(buffer)
  new Uint8Array(buffer).set(new TextEncoder().encode('HHT1'), 0)
  view.setUint32(4, headerBytes.length, true)
  new Uint8Array(buffer).set(headerBytes, 8)

  let offset = unpadded + padding
  for (const channel of channels) {
    for (const value of channel.values) {
      if (channel.type === 'u16') view.setUint16(offset, value, true)
      else if (channel.type === 'u32') view.setUint32(offset, value, true)
      else if (channel.type === 'f32') view.setFloat32(offset, value, true)
      else view.setFloat64(offset, value, true)
      offset += bytes[channel.type]!
    }
  }
  return buffer
}

describe('the reader', () => {
  it('copies a channel the format leaves unaligned rather than refusing to open it', () => {
    // Only the first payload is padded into alignment. An odd number of u32 samples pushes
    // the f64 channel behind it onto a 4-byte boundary, which no Float64Array can view.
    const buffer = encode(3, [
      { name: 't', type: 'u32', values: [0, 1000, 2000] },
      { name: 'lat', type: 'f64', values: [55.75, 55.76, 55.77] },
    ])

    const hht = readHht(buffer)
    expect(hht.values('lat')![1]).toBeCloseTo(55.76, 9)
    expect(hht.channel('lat')!.copied).toBe(true)
    expect(hht.channel('t')!.copied).toBe(false)
  })

  it('un-gzips a payload the transport handed over still compressed', async () => {
    // The Workers runtime compresses a response that already carries Content-Encoding: gzip,
    // so the client unwraps one layer and is left holding the stored object.
    const compressed = gzipSync(Buffer.from(golden()))
    const buffer = compressed.buffer.slice(
      compressed.byteOffset,
      compressed.byteOffset + compressed.byteLength,
    )

    const hht = readHht(await decompressIfNeeded(buffer))
    expect(hht.header.activityId).toBe('golden-v1')
  })

  it('passes an already-decompressed payload straight through', async () => {
    const buffer = golden()
    expect(await decompressIfNeeded(buffer)).toBe(buffer)
  })

  it('refuses an unknown magic instead of misreading it', () => {
    const buffer = new ArrayBuffer(64)
    new Uint8Array(buffer).set(new TextEncoder().encode('HHT9'), 0)
    expect(() => readHht(buffer)).toThrow(HhtFormatError)
  })

  it('refuses a header that claims more bytes than the object holds', () => {
    const buffer = encode(1, [{ name: 't', type: 'u32', values: [0] }])
    new DataView(buffer).setUint32(4, 10_000, true)
    expect(() => readHht(buffer)).toThrow(HhtFormatError)
  })

  it('refuses a channel that runs past the end of the object', () => {
    const buffer = encode(2, [{ name: 't', type: 'u32', values: [0, 1000] }])
    expect(() => readHht(buffer.slice(0, buffer.byteLength - 4)).values('t')).toThrow(
      HhtFormatError,
    )
  })
})
