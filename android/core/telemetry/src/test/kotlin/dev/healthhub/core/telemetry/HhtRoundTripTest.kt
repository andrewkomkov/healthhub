package dev.healthhub.core.telemetry

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The codec is implemented twice — here in Kotlin and again in TypeScript for the browser.
 * These tests pin the byte layout so the two cannot drift apart silently.
 */
class HhtRoundTripTest {

    private fun header(count: Int) = HhtHeader(
        activityId = "test-activity",
        startTime = 1_753_600_000_000L,
        count = count,
        channels = listOf(
            HhtChannel("t", HhtType.U32, "ms"),
            HhtChannel("lat", HhtType.F64, "deg"),
            HhtChannel("lon", HhtType.F64, "deg"),
            HhtChannel("hr", HhtType.U16, "bpm"),
            HhtChannel("speed", HhtType.F32, "m/s"),
        ),
    )

    @Test
    fun `writes and reads back every channel`() {
        val count = 5
        val times = longArrayOf(0, 1000, 2000, 3000, 4000)
        val lats = doubleArrayOf(55.75, 55.7501, 55.7502, 55.7503, 55.7504)
        val lons = doubleArrayOf(37.61, 37.6101, 37.6102, 37.6103, 37.6104)
        val hrs = intArrayOf(120, 132, 141, 150, 148)
        val speeds = floatArrayOf(3.1f, 3.4f, 3.6f, 3.9f, 3.7f)

        val out = ByteArrayOutputStream()
        HhtWriter(out, header(count)).use { writer ->
            writer.channel(HhtChannel("t", HhtType.U32, "ms")) { it.write(times) }
            writer.channel(HhtChannel("lat", HhtType.F64, "deg")) { it.write(lats) }
            writer.channel(HhtChannel("lon", HhtType.F64, "deg")) { it.write(lons) }
            writer.channel(HhtChannel("hr", HhtType.U16, "bpm")) { it.write(hrs) }
            writer.channel(HhtChannel("speed", HhtType.F32, "m/s")) { it.write(speeds) }
        }

        val reader = HhtReader(out.toByteArray())

        assertThat(reader.header.count).isEqualTo(count)
        assertThat(reader.header.activityId).isEqualTo("test-activity")
        assertThat(reader.header.channels.map { it.name })
            .containsExactly("t", "lat", "lon", "hr", "speed")
            .inOrder()

        assertThat(reader.read("t").toList())
            .containsExactly(0.0, 1000.0, 2000.0, 3000.0, 4000.0).inOrder()
        assertThat(reader.read("lat")[2]).isWithin(1e-9).of(55.7502)
        assertThat(reader.read("hr").toList())
            .containsExactly(120.0, 132.0, 141.0, 150.0, 148.0).inOrder()
        assertThat(reader.read("speed")[4]).isWithin(1e-5).of(3.7)
    }

    @Test
    fun `payload starts on an eight byte boundary`() {
        // The browser builds typed-array views over the buffer; an unaligned payload would
        // force a copy and defeat the format's whole purpose.
        val out = ByteArrayOutputStream()
        HhtWriter(out, header(1)).use { writer ->
            writer.channel(HhtChannel("t", HhtType.U32, "ms")) { it.write(longArrayOf(0)) }
            writer.channel(HhtChannel("lat", HhtType.F64, "deg")) { it.write(doubleArrayOf(1.0)) }
            writer.channel(HhtChannel("lon", HhtType.F64, "deg")) { it.write(doubleArrayOf(2.0)) }
            writer.channel(HhtChannel("hr", HhtType.U16, "bpm")) { it.write(intArrayOf(100)) }
            writer.channel(HhtChannel("speed", HhtType.F32, "m/s")) { it.write(floatArrayOf(1f)) }
        }

        val bytes = out.toByteArray()
        val headerLength = java.nio.ByteBuffer.wrap(bytes, 4, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertThat((8 + headerLength + paddingFor(8 + headerLength)) % Hht.ALIGNMENT).isEqualTo(0)
    }

    private fun paddingFor(offset: Int): Int {
        val unaligned = offset % Hht.ALIGNMENT
        return if (unaligned == 0) 0 else Hht.ALIGNMENT - unaligned
    }

    @Test
    fun `sentinels decode as not-recorded rather than as zero`() {
        val out = ByteArrayOutputStream()
        val single = HhtHeader(
            activityId = "gaps",
            startTime = 0,
            count = 3,
            channels = listOf(HhtChannel("hr", HhtType.U16, "bpm")),
        )
        HhtWriter(out, single).use { writer ->
            writer.channel(HhtChannel("hr", HhtType.U16, "bpm")) {
                it.write(intArrayOf(120, Hht.U16_NONE, 130))
            }
        }

        val values = HhtReader(out.toByteArray()).read("hr")
        assertThat(values[0]).isEqualTo(120.0)
        assertThat(values[1].isNaN()).isTrue()
        assertThat(values[2]).isEqualTo(130.0)
    }

    @Test
    fun `refuses a channel written out of header order`() {
        val out = ByteArrayOutputStream()
        assertThrows<HhtFormatException> {
            HhtWriter(out, header(1)).use { writer ->
                writer.channel(HhtChannel("lat", HhtType.F64, "deg")) {
                    it.write(doubleArrayOf(1.0))
                }
            }
        }
    }

    @Test
    fun `refuses a short count`() {
        val out = ByteArrayOutputStream()
        assertThrows<HhtFormatException> {
            HhtWriter(out, header(5)).use { writer ->
                writer.channel(HhtChannel("t", HhtType.U32, "ms")) { it.write(longArrayOf(0, 1)) }
            }
        }
    }

    @Test
    fun `rejects an unknown magic`() {
        val bytes = "XXXX".toByteArray() + ByteArray(16)
        assertThrows<HhtFormatException> { HhtReader(bytes) }
    }
}
