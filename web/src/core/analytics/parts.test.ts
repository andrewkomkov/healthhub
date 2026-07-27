import { describe, expect, it } from 'vitest'
import type { ArchiveMonth } from '../api/client'
import { archiveUrls, coverage, megabytes, monthKeys, partKey, s3Url } from './parts'

const month = (over: Partial<ArchiveMonth> = {}): ArchiveMonth => ({
  dataset: 'activities',
  year: 2026,
  month: 7,
  generation: 1,
  partCount: 1,
  rowCount: 42,
  bytes: 1024,
  compactedAt: 0,
  ...over,
})

describe('partKey', () => {
  it('writes the layout data-model.md specifies', () => {
    expect(partKey('u1', 'activities', 2026, 7, 1)).toBe(
      'u/u1/archive/activities/year=2026/month=07/part-0001.parquet',
    )
  })

  it('pads the month and the part index, so a Hive path sorts as a calendar', () => {
    expect(partKey('u1', 'activities', 2026, 12, 137)).toContain('month=12/part-0137.parquet')
  })
})

describe('monthKeys', () => {
  it('prefers the manifest over the layout, because the manifest is the authority', () => {
    const explicit = ['u/u1/archive/activities/year=2026/month=07/part-9999.parquet']
    expect(monthKeys('u1', month({ partCount: 4, parts: explicit }))).toEqual(explicit)
  })

  it('derives dense part names when the response only counted them', () => {
    expect(monthKeys('u1', month({ partCount: 3 }))).toEqual([
      'u/u1/archive/activities/year=2026/month=07/part-0001.parquet',
      'u/u1/archive/activities/year=2026/month=07/part-0002.parquet',
      'u/u1/archive/activities/year=2026/month=07/part-0003.parquet',
    ])
  })

  it('asks for nothing when a month holds no parts', () => {
    expect(monthKeys('u1', month({ partCount: 0 }))).toEqual([])
  })
})

describe('coverage', () => {
  const months = [
    month({ year: 2025, month: 11, rowCount: 10, bytes: 100, partCount: 1 }),
    month({ year: 2026, month: 1, rowCount: 20, bytes: 200, partCount: 2 }),
    month({ year: 2026, month: 7, rowCount: 12, bytes: 300, partCount: 1 }),
  ]

  it('sums what is actually compacted', () => {
    const summary = coverage(months)
    expect(summary.months).toBe(3)
    expect(summary.parts).toBe(4)
    expect(summary.rows).toBe(42)
    expect(summary.bytes).toBe(600)
    expect(summary.years).toEqual([2025, 2026])
    expect(summary.earliest).toEqual({ year: 2025, month: 11 })
    expect(summary.latest).toEqual({ year: 2026, month: 7 })
  })

  it('ignores the samples dataset, which is declared and deliberately never produced', () => {
    const summary = coverage([...months, month({ dataset: 'samples', rowCount: 999_999 })])
    expect(summary.rows).toBe(42)
  })

  it('has an honest empty shape', () => {
    expect(coverage([])).toMatchObject({ months: 0, parts: 0, rows: 0, years: [], latest: null })
  })
})

describe('archiveUrls', () => {
  it('lists every part, oldest first, as an s3 url', () => {
    const urls = archiveUrls('u1', 'bucket', [
      month({ year: 2026, month: 7 }),
      month({ year: 2025, month: 11 }),
    ])
    expect(urls).toEqual([
      's3://bucket/u/u1/archive/activities/year=2025/month=11/part-0001.parquet',
      's3://bucket/u/u1/archive/activities/year=2026/month=07/part-0001.parquet',
    ])
  })

  it('leaves out datasets nobody asked for', () => {
    expect(archiveUrls('u1', 'bucket', [month({ dataset: 'samples' })])).toEqual([])
  })
})

describe('s3Url', () => {
  it('does not mangle a key that already contains slashes', () => {
    expect(s3Url('b', 'u/1/archive/x.parquet')).toBe('s3://b/u/1/archive/x.parquet')
  })
})

describe('megabytes', () => {
  it('scales to something a person can read', () => {
    expect(megabytes(512)).toBe('512 B')
    expect(megabytes(2048)).toBe('2 kB')
    expect(megabytes(5 * 1024 * 1024)).toBe('5.0 MB')
  })
})
