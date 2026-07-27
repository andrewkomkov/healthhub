import { describe, expect, it } from 'vitest'
import { move, orderChanged, stepTo } from './reorder'

const list = ['strava', 'garmin', 'fit', 'samsung']

describe('move', () => {
  it('lifts an item up and shifts the rest down', () => {
    expect(move(list, 2, 0)).toEqual(['fit', 'strava', 'garmin', 'samsung'])
  })

  it('lifts an item down and shifts the rest up', () => {
    expect(move(list, 0, 2)).toEqual(['garmin', 'fit', 'strava', 'samsung'])
  })

  it('clamps past either end instead of wrapping', () => {
    expect(move(list, 1, -5)).toEqual(['garmin', 'strava', 'fit', 'samsung'])
    expect(move(list, 1, 99)).toEqual(['strava', 'fit', 'samsung', 'garmin'])
  })

  it('is a no-op when the item lands where it started', () => {
    expect(move(list, 2, 2)).toEqual(list)
  })

  it('never mutates the list it was given', () => {
    const before = [...list]
    move(list, 0, 3)
    expect(list).toEqual(before)
  })

  it('ignores a source index that is not in the list', () => {
    expect(move(list, 9, 0)).toEqual(list)
  })
})

describe('orderChanged', () => {
  const key = (value: string) => value

  it('sees a reordering', () => {
    expect(orderChanged(list, move(list, 0, 1), key)).toBe(true)
  })

  it('sees no change in a drag that ended where it began', () => {
    expect(orderChanged(list, move(list, 1, 1), key)).toBe(false)
  })
})

describe('stepTo', () => {
  it('walks one place at a time and stops at the ends', () => {
    expect(stepTo(1, -1, 4)).toBe(0)
    expect(stepTo(0, -1, 4)).toBe(0)
    expect(stepTo(3, 1, 4)).toBe(3)
    expect(stepTo(2, 1, 4)).toBe(3)
  })

  it('survives an empty list', () => {
    expect(stepTo(0, 1, 0)).toBe(0)
  })
})
