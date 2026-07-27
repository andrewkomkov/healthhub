package dev.healthhub.core.network

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The pre-hash exists twice — here and in `web/src/features/auth/prehash.ts` — and the two
 * halves never meet at runtime: an athlete registers on the phone and signs in in a browser.
 * `fixtures/auth/prehash-v1.json` is the only thing that fails when they drift apart.
 *
 * The Worker cannot produce these vectors and so cannot arbitrate: 600,000 iterations is six
 * times the PBKDF2 ceiling workerd enforces, which is the reason the client-side pass exists.
 */
class PasswordProofsTest {

    private val fixture = Json.parseToJsonElement(findFixture().readText()).jsonObject
    private val vectors = fixture["vectors"]!!.jsonArray.map { it.jsonObject }

    private fun findFixture(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, "fixtures/auth/prehash-v1.json")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        error("fixtures/auth/prehash-v1.json not found above ${System.getProperty("user.dir")}")
    }

    private fun text(key: String, from: JsonObject) = from[key]!!.jsonPrimitive.content

    @Test
    fun `uses the scheme the fixture names`() {
        assertThat(PasswordProofs.SCHEME).isEqualTo(text("scheme", fixture))
    }

    @Test
    fun `derives the salt every vector expects`() {
        for (vector in vectors) {
            val salt = PasswordProofs.saltFor(text("email", vector))
            assertThat(Base64.getEncoder().encodeToString(salt))
                .isEqualTo(text("saltBase64", vector))
        }
    }

    @Test
    fun `derives the proof every vector expects`() = runTest {
        for (vector in vectors) {
            val proof = PasswordProofs.proof(text("email", vector), text("password", vector))
            assertThat(proof.scheme).isEqualTo(text("scheme", fixture))
            assertThat(proof.value).isEqualTo(text("value", vector))
        }
    }

    /**
     * The address is normalised before it is hashed, so an athlete who types their address
     * with a capital letter is not locked out of their own account.
     */
    @Test
    fun `ignores case and surrounding space in the address`() {
        assertThat(PasswordProofs.saltFor("  A@Example.COM "))
            .isEqualTo(PasswordProofs.saltFor("a@example.com"))
    }

    @Test
    fun `gives two accounts different proofs for the same password`() = runTest {
        val one = PasswordProofs.proof("one@example.com", "correct-horse-battery")
        val two = PasswordProofs.proof("two@example.com", "correct-horse-battery")
        assertThat(one.value).isNotEqualTo(two.value)
    }

    @Test
    fun `produces a proof of exactly the width the Worker accepts`() = runTest {
        val proof = PasswordProofs.proof("a@example.com", "correct-horse-battery")
        assertThat(Base64.getDecoder().decode(proof.value))
            .hasLength(fixture["keyBytes"]!!.jsonPrimitive.int)
    }
}
