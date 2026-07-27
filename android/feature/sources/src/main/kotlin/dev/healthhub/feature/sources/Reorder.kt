package dev.healthhub.feature.sources

/**
 * The ordering primitive behind both ways of reordering the list.
 *
 * A long-press drag and a tap on the move buttons are the same operation with different input,
 * so they share one implementation and one set of tests. Nothing here touches Compose, which
 * is why the button path can be trusted without an instrumented run to prove it.
 *
 * Mirrors `web/src/features/sources/reorder.ts` rule for rule — the two clients write to the
 * same `PUT /api/sources`, so an off-by-one on one of them would show up as the phone and the
 * browser disagreeing about who is most trusted.
 */

/** Moves one item, shifting everything between. An out-of-range or no-op move changes nothing. */
internal fun <T> List<T>.movedItem(from: Int, to: Int): List<T> {
    if (from !in indices) return this
    val clamped = to.coerceIn(0, lastIndex)
    if (clamped == from) return this
    val next = toMutableList()
    next.add(clamped, next.removeAt(from))
    return next
}

/** True when two orderings of the same set differ — the test for "is a save worth making". */
internal fun <T> orderChanged(a: List<T>, b: List<T>, key: (T) -> String): Boolean {
    if (a.size != b.size) return true
    return a.indices.any { key(a[it]) != key(b[it]) }
}

/**
 * Where a move button sends the item.
 *
 * Deliberately clamped rather than wrapping: an athlete tapping "down" to reach the bottom of
 * the list would otherwise find their most trusted source back at the top.
 */
internal fun stepTo(index: Int, direction: Int, length: Int): Int =
    (index + direction).coerceIn(0, (length - 1).coerceAtLeast(0))
