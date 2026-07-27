package dev.healthhub.core.telemetry

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Writes a `.hht` object one channel at a time.
 *
 * Channels are written in chunks and never assembled into a single buffer, because a
 * five-hour ride is around a million samples per channel and holding all of them at once is
 * exactly how the app gets killed on a mid-range phone (SC-003). The caller streams whatever
 * chunk size is convenient; this class only guarantees ordering and alignment.
 *
 * Usage:
 * ```
 * HhtWriter(out, header).use { writer ->
 *     writer.channel(timeChannel) { it.write(timesChunk) }
 *     writer.channel(hrChannel) { it.write(hrChunk) }
 * }
 * ```
 */
class HhtWriter(
    output: OutputStream,
    private val header: HhtHeader,
) : AutoCloseable {

    private val out = DataOutputStream(BufferedOutputStream(output, BUFFER_BYTES))
    private var channelIndex = 0
    private var writtenInChannel = 0
    private var headerWritten = false

    /** A cursor over one channel's payload, handing out primitive writes. */
    inner class ChannelSink internal constructor(private val channel: HhtChannel) {

        fun write(values: FloatArray, length: Int = values.size) {
            require(channel.type == HhtType.F32) { "${channel.name} is ${channel.type}, not f32" }
            val buffer = buffer(length * 4)
            for (i in 0 until length) buffer.putFloat(values[i])
            flush(buffer, length)
        }

        fun write(values: DoubleArray, length: Int = values.size) {
            require(channel.type == HhtType.F64) { "${channel.name} is ${channel.type}, not f64" }
            val buffer = buffer(length * 8)
            for (i in 0 until length) buffer.putDouble(values[i])
            flush(buffer, length)
        }

        fun write(values: IntArray, length: Int = values.size) {
            val buffer = buffer(length * channel.type.bytes)
            when (channel.type) {
                HhtType.U16 -> for (i in 0 until length) buffer.putShort(values[i].toShort())
                HhtType.U32 -> for (i in 0 until length) buffer.putInt(values[i])
                else -> throw IllegalArgumentException("${channel.name} is ${channel.type}, not an integer channel")
            }
            flush(buffer, length)
        }

        fun write(values: LongArray, length: Int = values.size) {
            require(channel.type == HhtType.U32) { "${channel.name} is ${channel.type}, not u32" }
            val buffer = buffer(length * 4)
            for (i in 0 until length) buffer.putInt(values[i].toInt())
            flush(buffer, length)
        }

        private fun buffer(bytes: Int) =
            ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)

        private fun flush(buffer: ByteBuffer, samples: Int) {
            out.write(buffer.array(), 0, buffer.position())
            writtenInChannel += samples
        }
    }

    /**
     * Writes one channel's payload. Channels must be supplied in the order they appear in the
     * header — the format is positional, and a reader computes each channel's offset by
     * summing the sizes of the ones before it.
     */
    fun channel(channel: HhtChannel, body: (ChannelSink) -> Unit) {
        if (!headerWritten) writeHeader()

        val expected = header.channels.getOrNull(channelIndex)
            ?: throw HhtFormatException("More channels written than the header declares")
        if (expected.name != channel.name) {
            throw HhtFormatException(
                "Channels must be written in header order: expected '${expected.name}', got '${channel.name}'",
            )
        }

        writtenInChannel = 0
        body(ChannelSink(channel))

        if (writtenInChannel != header.count) {
            throw HhtFormatException(
                "Channel '${channel.name}' wrote $writtenInChannel samples, header declares ${header.count}",
            )
        }
        channelIndex += 1
    }

    private fun writeHeader() {
        val json = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "v" to JsonPrimitive(header.version),
                    "activityId" to JsonPrimitive(header.activityId),
                    "startTime" to JsonPrimitive(header.startTime),
                    "count" to JsonPrimitive(header.count),
                    "channels" to JsonArray(
                        header.channels.map { channel ->
                            JsonObject(
                                mapOf(
                                    "name" to JsonPrimitive(channel.name),
                                    "type" to JsonPrimitive(channel.type.wire),
                                    "unit" to JsonPrimitive(channel.unit),
                                    "scale" to JsonPrimitive(channel.scale),
                                ),
                            )
                        },
                    ),
                ),
            ),
        )

        val headerBytes = json.toByteArray(Charsets.UTF_8)
        out.writeBytes(Hht.MAGIC)
        out.write(
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(headerBytes.size).array(),
        )
        out.write(headerBytes)

        // Pad so the first payload starts aligned; without this the browser has to copy
        // rather than view the buffer, which defeats the whole point of the format.
        val unaligned = (Hht.MAGIC.length + 4 + headerBytes.size) % Hht.ALIGNMENT
        if (unaligned != 0) out.write(ByteArray(Hht.ALIGNMENT - unaligned))

        headerWritten = true
    }

    override fun close() {
        if (!headerWritten) writeHeader()
        if (channelIndex != header.channels.size) {
            throw HhtFormatException(
                "Header declares ${header.channels.size} channels but only $channelIndex were written",
            )
        }
        out.flush()
        out.close()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
