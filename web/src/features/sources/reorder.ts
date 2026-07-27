/**
 * The ordering primitive behind both ways of reordering this list.
 *
 * A pointer drag and an arrow-key move are the same operation with different input, so they
 * share one implementation and one set of tests. Nothing here touches the DOM, which is why
 * the keyboard path can be trusted without a browser to prove it.
 */

/** Moves one item, shifting everything between. Out-of-range or no-op moves change nothing. */
export function move<T>(items: readonly T[], from: number, to: number): T[] {
  const next = [...items]
  if (from < 0 || from >= next.length) return next
  const clamped = Math.min(Math.max(to, 0), next.length - 1)
  if (clamped === from) return next
  const [item] = next.splice(from, 1)
  next.splice(clamped, 0, item as T)
  return next
}

/** True when two orderings of the same set differ — the test for "is a save worth making". */
export function orderChanged<T>(a: readonly T[], b: readonly T[], key: (item: T) => string) {
  if (a.length !== b.length) return true
  return a.some((item, index) => key(item) !== key(b[index] as T))
}

/**
 * Where an arrow key sends the grabbed item.
 *
 * Deliberately clamped rather than wrapping: an athlete holding ArrowDown to reach the bottom
 * of the list would otherwise find their most trusted source at the top again.
 */
export function stepTo(index: number, direction: -1 | 1, length: number): number {
  return Math.min(Math.max(index + direction, 0), Math.max(length - 1, 0))
}
