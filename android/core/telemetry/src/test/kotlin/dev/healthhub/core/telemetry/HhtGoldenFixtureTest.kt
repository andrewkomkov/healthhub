package dev.healthhub.core.telemetry

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.jupiter.api.Test

/**
 * The golden `.hht` fixture — the contract between the two implementations of this codec.
 *
 * `fixtures/hht/golden-v1.hht` is written by this test and read by the TypeScript reader's
 * test in `web/src/core/telemetry/hht.test.ts`. If the Kotlin writer's byte layout changes,
 * this test fails; if the TypeScript reader drifts from it, that one does. Neither can move
 * without the other noticing, which is the entire point of implementing the format twice.
 *
 * The sample values are deliberately awkward: every storage type appears, every sentinel
 * appears, and there is a GPS gap in the middle so the "render a gap, not a straight line
 * through a tunnel" rule has something to be tested against.
 */
class HhtGoldenFixtureTest {

    @Test
    fun `the committed fixture is exactly what the writer produces`() {
        val produced = writeFixture()
        val file = fixtureFile()

        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeBytes(produced)
            error("Golden fixture was missing and has been written to $file — commit it.")
        }

        val committed = file.readBytes()
        if (!committed.contentEquals(produced)) {
            // Rewriting on failure would defeat the purpose; the mismatch has to be looked at.
            error(
                "Golden fixture at $file no longer matches the writer " +
                    "(committed ${committed.size} bytes, produced ${produced.size}). " +
                    "Either the format changed on purpose — then update the fixture and the " +
                    "TypeScript reader together — or something regressed.",
            )
        }
    }

    @Test
    fun `the fixture decodes to the values the TypeScript test also asserts`() {
        val reader = HhtReader(writeFixture())

        assertThat(reader.header.version).isEqualTo(1)
        assertThat(reader.header.activityId).isEqualTo(ACTIVITY_ID)
        assertThat(reader.header.startTime).isEqualTo(START_TIME)
        assertThat(reader.header.count).isEqualTo(COUNT)
        assertThat(reader.header.channels.map { it.name })
            .containsExactly("t", "lat", "lon", "elevation", "hr", "speed", "cadence", "power")
            .inOrder()

        assertThat(reader.read("t")[COUNT - 1]).isEqualTo(11_000.0)
        assertThat(reader.read("lat")[0]).isWithin(1e-9).of(55.75000)
        assertThat(reader.read("lon")[11]).isWithin(1e-9).of(37.61110)

        // The tunnel: samples 5 and 6 have no fix at all.
        val lat = reader.read("lat")
        assertThat(lat[5].isNaN()).isTrue()
        assertThat(lat[6].isNaN()).isTrue()
        assertThat(lat[7].isNaN()).isFalse()

        // A dropped heart-rate sample is unknown, not zero.
        val hr = reader.read("hr")
        assertThat(hr[0]).isEqualTo(118.0)
        assertThat(hr[3].isNaN()).isTrue()

        // A ride with no power meter still writes the channel for part of the session.
        val power = reader.read("power")
        assertThat(power[0]).isEqualTo(210.0)
        assertThat(power[COUNT - 1].isNaN()).isTrue()

        assertThat(reader.read("speed")[2]).isWithin(1e-5).of(5.8)
        assertThat(reader.read("elevation")[2]).isWithin(1e-4).of(146.25)
        assertThat(reader.read("cadence")[4].isNaN()).isTrue()
    }

    private fun writeFixture(): ByteArray {
        val out = ByteArrayOutputStream()
        HhtWriter(out, HEADER).use { writer ->
            writer.channel(HEADER.channels[0]) { it.write(TIMES) }
            writer.channel(HEADER.channels[1]) { it.write(LATS) }
            writer.channel(HEADER.channels[2]) { it.write(LONS) }
            writer.channel(HEADER.channels[3]) { it.write(ELEVATION) }
            writer.channel(HEADER.channels[4]) { it.write(HR) }
            writer.channel(HEADER.channels[5]) { it.write(SPEED) }
            writer.channel(HEADER.channels[6]) { it.write(CADENCE) }
            writer.channel(HEADER.channels[7]) { it.write(POWER) }
        }
        return out.toByteArray()
    }

    /**
     * The fixture is shared with the web workspace, so it lives at the repository root rather
     * than in either module's resources. Walking up beats a relative path: Gradle's working
     * directory for a test worker is not something to rely on.
     */
    private fun fixtureFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "package.json").isFile && File(directory, "web").isDirectory) {
                return File(directory, "fixtures/hht/golden-v1.hht")
            }
            directory = directory.parentFile
        }
        error("Could not find the repository root from ${File("").absolutePath}")
    }

    private companion object {
        const val ACTIVITY_ID = "golden-v1"
        const val START_TIME = 1_753_600_000_000L
        const val COUNT = 12

        val HEADER = HhtHeader(
            activityId = ACTIVITY_ID,
            startTime = START_TIME,
            count = COUNT,
            channels = listOf(
                HhtChannel("t", HhtType.U32, "ms"),
                HhtChannel("lat", HhtType.F64, "deg"),
                HhtChannel("lon", HhtType.F64, "deg"),
                HhtChannel("elevation", HhtType.F32, "m"),
                HhtChannel("hr", HhtType.U16, "bpm"),
                HhtChannel("speed", HhtType.F32, "m/s"),
                HhtChannel("cadence", HhtType.F32, "rpm"),
                HhtChannel("power", HhtType.U16, "W"),
            ),
        )

        val TIMES = LongArray(COUNT) { it * 1000L }

        // A short stretch east along a constant latitude, with two samples of tunnel.
        val LATS = DoubleArray(COUNT) { i ->
            if (i == 5 || i == 6) Double.NaN else 55.75 + i * 0.00001
        }
        val LONS = DoubleArray(COUNT) { i ->
            if (i == 5 || i == 6) Double.NaN else 37.61 + i * 0.00010
        }
        val ELEVATION = FloatArray(COUNT) { i -> 145.0f + i * 0.625f }
        val HR = IntArray(COUNT) { i -> if (i == 3) Hht.U16_NONE else 118 + i * 2 }
        val SPEED = FloatArray(COUNT) { i -> 5.6f + i * 0.1f }

        // Cadence stops being reported halfway through — a sensor dropout, not a stop.
        val CADENCE = FloatArray(COUNT) { i ->
            if (i in 4..8) Float.NaN else 84.0f + i
        }
        val POWER = IntArray(COUNT) { i ->
            if (i >= COUNT - 2) Hht.U16_NONE else 210 + i * 5
        }
    }
}
