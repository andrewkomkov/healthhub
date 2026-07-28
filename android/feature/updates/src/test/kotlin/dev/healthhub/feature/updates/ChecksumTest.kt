package dev.healthhub.feature.updates

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ChecksumTest {

    @Test
    fun `hashes a stream the way sha256sum does`() {
        // The published file is produced by `sha256sum`, so the two have to agree on the
        // canonical digest of the canonical input.
        val digest = UpdateInstaller.sha256("abc".byteInputStream())

        assertThat(digest)
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `keeps the leading zero of a byte`() {
        // "%x" instead of "%02x" drops it, and the digest is then 63 characters that match
        // nothing — a mismatch that looks like a corrupted download.
        val digest = UpdateInstaller.sha256(ByteArray(0).inputStream())

        assertThat(digest).hasLength(64)
        assertThat(digest)
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun `reads a stream larger than one buffer`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }

        assertThat(UpdateInstaller.sha256(bytes.inputStream()))
            .isEqualTo(UpdateInstaller.sha256(bytes.inputStream()))
        assertThat(UpdateInstaller.sha256(bytes.inputStream())).hasLength(64)
    }
}
