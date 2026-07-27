package dev.healthhub.core.telemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads a `.hht` object.
 *
 * Used on Android to re-read what was staged, and by the round-trip test that keeps this
 * implementation honest against the TypeScript reader in the web client.
 *
 * Decoded channels come back as `DoubleArray` for uniformity; sentinels are converted to
 * `NaN` so a caller can skip them with a single check regardless of the stored type.
 */
class HhtReader(private val bytes: ByteArray) {

    val header: HhtHeader
    private val payloadStart: Int

    init {
        if (bytes.size < 8) throw HhtFormatException("Object is too short to be a .hht")

        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        if (magic != Hht.MAGIC) {
            // A future format bumps the magic; refusing loudly beats silently misreading.
            throw HhtFormatException("Unexpected magic '$magic', expected '${Hht.MAGIC}'")
        }

        val headerLength = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (headerLength <= 0 || 8 + headerLength > bytes.size) {
            throw HhtFormatException("Header length $headerLength is out of range")
        }

        val json = Json.parseToJsonElement(String(bytes, 8, headerLength, Charsets.UTF_8)).jsonObject
        header = HhtHeader(
            version = json["v"]!!.jsonPrimitive.content.toInt(),
            activityId = json["activityId"]!!.jsonPrimitive.content,
            startTime = json["startTime"]!!.jsonPrimitive.content.toLong(),
            count = json["count"]!!.jsonPrimitive.content.toInt(),
            channels = json["channels"]!!.jsonArray.map { element ->
                val channel = element.jsonObject
                HhtChannel(
                    name = channel["name"]!!.jsonPrimitive.content,
                    type = HhtType.fromWire(channel["type"]!!.jsonPrimitive.content),
                    unit = channel["unit"]?.jsonPrimitive?.content ?: "",
                    scale = channel["scale"]?.jsonPrimitive?.content?.toDouble() ?: 1.0,
                )
            },
        )

        val unpadded = 8 + headerLength
        val unaligned = unpadded % Hht.ALIGNMENT
        payloadStart = if (unaligned == 0) unpadded else unpadded + (Hht.ALIGNMENT - unaligned)
    }

    /** Byte offset of a channel's payload — the sum of the sizes of everything before it. */
    private fun offsetOf(channel: HhtChannel): Int {
        var offset = payloadStart
        for (candidate in header.channels) {
            if (candidate.name == channel.name) return offset
            offset += (candidate.type.bytes * header.count)
        }
        throw HhtFormatException("Channel '${channel.name}' is not in this object")
    }

    fun channel(name: String): HhtChannel? = header.channels.firstOrNull { it.name == name }

    /** Decodes one channel, mapping sentinels to NaN. */
    fun read(name: String): DoubleArray {
        val channel = channel(name) ?: throw HhtFormatException("No channel '$name'")
        val buffer = ByteBuffer.wrap(bytes, offsetOf(channel), channel.type.bytes * header.count)
            .order(ByteOrder.LITTLE_ENDIAN)

        val values = DoubleArray(header.count)
        when (channel.type) {
            HhtType.U16 -> for (i in values.indices) {
                val raw = buffer.short.toInt() and 0xFFFF
                values[i] = if (raw == Hht.U16_NONE) Double.NaN else raw * channel.scale
            }
            HhtType.U32 -> for (i in values.indices) {
                val raw = buffer.int.toLong() and 0xFFFFFFFFL
                values[i] = if (raw == Hht.U32_NONE) Double.NaN else raw * channel.scale
            }
            HhtType.F32 -> for (i in values.indices) {
                val raw = buffer.float
                values[i] = if (raw.isNaN()) Double.NaN else raw * channel.scale
            }
            HhtType.F64 -> for (i in values.indices) {
                val raw = buffer.double
                values[i] = if (raw.isNaN()) Double.NaN else raw * channel.scale
            }
        }
        return values
    }
}
