package dev.healthhub.core.telemetry

/**
 * The `.hht` telemetry container.
 *
 * Layout (see specs/001-workout-sync-feed/data-model.md):
 *
 * ```
 * offset  size   contents
 * 0       4      magic "HHT1"
 * 4       4      uint32 LE — header length
 * 8       N      UTF-8 JSON header
 * ...     pad    to an 8-byte boundary
 * ...     ...    channel payloads, contiguous, in header order
 * ```
 *
 * The point of the format is that a reader can build typed-array views straight over the
 * buffer with no parsing — which is what makes a 100k-sample activity interactive in the
 * browser within three seconds. Everything here exists to keep that property.
 */
object Hht {
    const val MAGIC = "HHT1"
    const val VERSION = 1

    /** Payload alignment, so the browser can create typed-array views without copying. */
    const val ALIGNMENT = 8

    /** Missing-sample sentinels. Readers skip these rather than plotting them — that is how
     *  a GPS gap renders as a gap instead of a straight line through a tunnel. */
    const val U16_NONE = 0xFFFF
    const val U32_NONE = 0xFFFFFFFFL
    val F32_NONE = Float.NaN
    val F64_NONE = Double.NaN
}

/** The storage type of one channel. */
enum class HhtType(val wire: String, val bytes: Int) {
    U16("u16", 2),
    U32("u32", 4),
    F32("f32", 4),
    F64("f64", 8);

    companion object {
        private val byWire = entries.associateBy(HhtType::wire)
        fun fromWire(wire: String): HhtType =
            byWire[wire] ?: throw HhtFormatException("Unknown channel type '$wire'")
    }
}

data class HhtChannel(
    val name: String,
    val type: HhtType,
    val unit: String,
    val scale: Double = 1.0,
)

data class HhtHeader(
    val version: Int = Hht.VERSION,
    val activityId: String,
    val startTime: Long,
    val count: Int,
    val channels: List<HhtChannel>,
) {
    init {
        require(count >= 0) { "count must not be negative" }
    }

    /** Byte size of one channel's payload. */
    fun payloadBytes(channel: HhtChannel): Long = channel.type.bytes.toLong() * count
}

class HhtFormatException(message: String) : IllegalArgumentException(message)
