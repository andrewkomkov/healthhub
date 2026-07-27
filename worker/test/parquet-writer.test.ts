import { describe, expect, it } from 'vitest'
import { parquetWriteBuffer } from 'hyparquet-writer'

/**
 * Proves the Parquet writer runs inside workerd, which is the whole reason this dependency
 * was chosen over the popular ones: the maintained `parquetjs` forks reach for `fs`, `thrift`
 * and the AWS SDK, none of which belong in a Worker. This test exists so that session 5 finds
 * out here rather than at deploy time.
 */
describe('parquet writer on workerd', () => {
  it('writes a buffer with the parquet magic at both ends', () => {
    const buffer = parquetWriteBuffer({
      columnData: [
        { name: 'sport', data: ['cycling', 'running'], type: 'STRING' },
        { name: 'distance_m', data: [92310.4, 10240.0], type: 'DOUBLE' },
      ],
    })

    const bytes = new Uint8Array(buffer)
    const magic = (offset: number) => String.fromCharCode(...bytes.slice(offset, offset + 4))

    expect(magic(0)).toBe('PAR1')
    expect(magic(bytes.length - 4)).toBe('PAR1')
  })
})
