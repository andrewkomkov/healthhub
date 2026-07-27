/**
 * The `.hht` reader — the browser half of a codec that is implemented twice.
 *
 * The Kotlin writer in `core:telemetry` produces these objects; both sides are pinned to
 * `fixtures/hht/golden-v1.hht` so neither can drift without a test failing.
 *
 * The design goal is that opening an activity costs no parse pass: channels become
 * typed-array views over the response's own `ArrayBuffer`, and a 100k-sample ride is ready to
 * plot in the time it took to transfer. Everything below exists to preserve that property,
 * and `copied` on each channel is how you tell when it was not achievable.
 */

export const HHT_MAGIC = 'HHT1'
export const HHT_ALIGNMENT = 8

/** Missing-sample sentinels, matching `Hht.kt`. Readers skip these; they are not zero. */
export const U16_NONE = 0xffff
export const U32_NONE = 0xffffffff

export type HhtType = 'u16' | 'u32' | 'f32' | 'f64'

const BYTES: Record<HhtType, number> = { u16: 2, u32: 4, f32: 4, f64: 8 }

export interface HhtChannel {
  name: string
  type: HhtType
  unit: string
  scale: number
  /** Byte offset of this channel's payload within the object. */
  byteOffset: number
  /** True when the payload had to be copied to be viewable — see `viewOf`. */
  copied: boolean
}

export interface HhtHeader {
  version: number
  activityId: string
  startTime: number
  count: number
  channels: HhtChannel[]
}

export class HhtFormatError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'HhtFormatError'
  }
}

interface RawHeaderChannel {
  name: string
  type: string
  unit?: string
  scale?: number
}

/**
 * One decoded telemetry object.
 *
 * `raw()` hands back the stored representation, sentinels and all. `values()` is what charts
 * and maps use: `Float64Array` with scale applied and every sentinel turned into `NaN`, so a
 * consumer has one check to make regardless of how the channel was stored. That conversion is
 * the only pass over the data this class performs, and it is memoised per channel.
 */
export class HhtObject {
  private readonly views = new Map<string, ArrayBufferView>()
  private readonly decoded = new Map<string, Float64Array>()

  constructor(
    readonly header: HhtHeader,
    private readonly buffer: ArrayBuffer,
  ) {}

  get count(): number {
    return this.header.count
  }

  get startTime(): number {
    return this.header.startTime
  }

  get channelNames(): string[] {
    return this.header.channels.map((channel) => channel.name)
  }

  channel(name: string): HhtChannel | undefined {
    return this.header.channels.find((candidate) => candidate.name === name)
  }

  /** An absent channel means "the source never recorded it" — never zero. */
  has(name: string): boolean {
    return this.channel(name) !== undefined
  }

  /** The stored representation, as a view over the original buffer where alignment allows. */
  raw(name: string): ArrayBufferView | null {
    const channel = this.channel(name)
    if (!channel) return null

    const cached = this.views.get(name)
    if (cached) return cached

    const view = viewOf(this.buffer, channel, this.header.count)
    this.views.set(name, view)
    return view
  }

  /**
   * Scaled values with sentinels as `NaN`.
   *
   * A float channel is already in that shape, so `f64` is returned as the view itself rather
   * than a copy — which matters, because `lat` and `lon` on a long ride are the two largest
   * channels in the object.
   */
  values(name: string): Float64Array | null {
    const cached = this.decoded.get(name)
    if (cached) return cached

    const channel = this.channel(name)
    const view = this.raw(name)
    if (!channel || !view) return null

    let out: Float64Array
    if (channel.type === 'f64' && channel.scale === 1) {
      out = view as Float64Array
    } else {
      out = new Float64Array(this.header.count)
      const source = view as unknown as { [index: number]: number }
      const scale = channel.scale
      const sentinel = channel.type === 'u16' ? U16_NONE : channel.type === 'u32' ? U32_NONE : NaN
      for (let i = 0; i < out.length; i++) {
        const value = source[i] as number
        out[i] = value === sentinel || Number.isNaN(value) ? NaN : value * scale
      }
    }

    this.decoded.set(name, out)
    return out
  }

  /** Absolute epoch milliseconds for a sample, since `t` is relative to `startTime`. */
  timestampAt(index: number): number {
    const t = this.values('t')
    return this.header.startTime + (t ? (t[index] ?? 0) : 0)
  }
}

/**
 * Builds a typed-array view, copying only when the payload is not naturally aligned.
 *
 * The format pads after the header so the *first* payload is 8-byte aligned, but channels are
 * contiguous after that: a `u32` time channel with an odd sample count leaves every following
 * `f64` on a 4-byte boundary, which `Float64Array` refuses to view. Copying that channel is
 * the honest fallback — around a millisecond for 100k samples, versus refusing to open the
 * activity at all. `channel.copied` records that it happened.
 */
function viewOf(buffer: ArrayBuffer, channel: HhtChannel, count: number): ArrayBufferView {
  const bytes = BYTES[channel.type]
  const length = bytes * count
  if (channel.byteOffset + length > buffer.byteLength) {
    throw new HhtFormatError(
      `Channel '${channel.name}' runs past the end of the object ` +
        `(needs ${channel.byteOffset + length} bytes, object is ${buffer.byteLength})`,
    )
  }

  const aligned = channel.byteOffset % bytes === 0
  channel.copied = !aligned
  const source = aligned ? buffer : buffer.slice(channel.byteOffset, channel.byteOffset + length)
  const offset = aligned ? channel.byteOffset : 0

  switch (channel.type) {
    case 'u16':
      return new Uint16Array(source, offset, count)
    case 'u32':
      return new Uint32Array(source, offset, count)
    case 'f32':
      return new Float32Array(source, offset, count)
    case 'f64':
      return new Float64Array(source, offset, count)
  }
}

function typeOf(wire: string): HhtType {
  if (wire === 'u16' || wire === 'u32' || wire === 'f32' || wire === 'f64') return wire
  throw new HhtFormatError(`Unknown channel type '${wire}'`)
}

/** gzip's magic number, in the first two bytes of every member. */
const GZIP_MAGIC = [0x1f, 0x8b]

/**
 * Un-gzips the payload when it arrives still compressed.
 *
 * Telemetry is stored gzipped and served with `Content-Encoding: gzip`, so in principle the
 * transport decodes it before `arrayBuffer()` resolves. In practice it may not: the Workers
 * runtime compresses a response *again* when it already carries that header, and the client
 * unwraps exactly one layer, handing us the stored object still compressed. Sniffing the
 * magic number handles both worlds, and costs two byte comparisons on the path where the
 * transport did its job.
 */
export async function decompressIfNeeded(buffer: ArrayBuffer): Promise<ArrayBuffer> {
  const head = new Uint8Array(buffer, 0, Math.min(2, buffer.byteLength))
  if (head[0] !== GZIP_MAGIC[0] || head[1] !== GZIP_MAGIC[1]) return buffer

  const source = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new Uint8Array(buffer))
      controller.close()
    },
  })
  // The cast is a type-level clash, not a runtime one: the workspace pulls in @types/node for
  // the fixture-reading tests, whose stream globals shadow lib.dom's, and its `pipeThrough`
  // wants a writable of exactly `Uint8Array` where DOM's `DecompressionStream` declares
  // `BufferSource`. Both descriptions name the same platform object.
  const gunzip = new DecompressionStream('gzip') as unknown as ReadableWritablePair<
    Uint8Array,
    Uint8Array
  >
  return new Response(source.pipeThrough(gunzip)).arrayBuffer()
}

/**
 * Reads a `.hht` object from an already-decompressed buffer.
 *
 * Callers holding a network response should pass it through `decompressIfNeeded` first.
 */
export function readHht(buffer: ArrayBuffer): HhtObject {
  if (buffer.byteLength < HHT_ALIGNMENT) {
    throw new HhtFormatError('Object is too short to be a .hht')
  }

  const bytes = new Uint8Array(buffer)
  const magic = String.fromCharCode(bytes[0]!, bytes[1]!, bytes[2]!, bytes[3]!)
  if (magic !== HHT_MAGIC) {
    // A future format bumps the magic; refusing loudly beats silently misreading.
    throw new HhtFormatError(`Unexpected magic '${magic}', expected '${HHT_MAGIC}'`)
  }

  const headerLength = new DataView(buffer).getUint32(4, true)
  if (headerLength <= 0 || 8 + headerLength > buffer.byteLength) {
    throw new HhtFormatError(`Header length ${headerLength} is out of range`)
  }

  const json = new TextDecoder().decode(new Uint8Array(buffer, 8, headerLength))
  let parsed: {
    v: number
    activityId: string
    startTime: number
    count: number
    channels: RawHeaderChannel[]
  }
  try {
    parsed = JSON.parse(json) as typeof parsed
  } catch {
    throw new HhtFormatError('Header is not valid JSON')
  }

  const unpadded = 8 + headerLength
  const unaligned = unpadded % HHT_ALIGNMENT
  let offset = unaligned === 0 ? unpadded : unpadded + (HHT_ALIGNMENT - unaligned)

  const count = parsed.count
  if (!Number.isInteger(count) || count < 0) {
    throw new HhtFormatError(`Header declares a nonsensical sample count: ${String(count)}`)
  }

  const channels: HhtChannel[] = (parsed.channels ?? []).map((raw) => {
    const type = typeOf(raw.type)
    const channel: HhtChannel = {
      name: raw.name,
      type,
      unit: raw.unit ?? '',
      scale: raw.scale ?? 1,
      byteOffset: offset,
      copied: false,
    }
    offset += BYTES[type] * count
    return channel
  })

  return new HhtObject(
    {
      version: parsed.v,
      activityId: parsed.activityId,
      startTime: parsed.startTime,
      count,
      channels,
    },
    buffer,
  )
}
