package dev.healthhub.core.network

import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's half of the password KDF (research.md R-006 amendment).
 *
 * The Workers runtime refuses PBKDF2 above 100,000 iterations, so the work that makes a stolen
 * password database expensive to attack cannot all happen at the edge. It happens here
 * instead: the phone derives 600,000 iterations' worth and sends the result, and the Worker
 * salts that result with a per-user random salt and hashes it at the only count it is allowed.
 * Neither half is sufficient alone.
 *
 * This lives in `core:network` rather than in `feature:auth` on purpose. It is part of the
 * wire format, and `HealthHubApi` is the one place every caller goes through — including the
 * debug `login` and `register` ADB commands, which would otherwise have their own idea of what
 * a password is.
 *
 * The same derivation exists in TypeScript, `web/src/features/auth/prehash.ts`. Both are
 * pinned to `fixtures/auth/prehash-v1.json`; if they drift, an athlete who signed up on this
 * phone cannot sign in in a browser, and nothing else would notice.
 */
object PasswordProofs {

    /**
     * The scheme name is the parameters, spelled out. Changing an iteration count or the salt
     * derivation means a new name — a stored record names the scheme it was built from, and a
     * proof under a name the record does not carry proves nothing.
     */
    const val SCHEME: String = "pbkdf2-sha256/600000/v1"

    private const val ITERATIONS = 600_000
    private const val KEY_BYTES = 32

    /**
     * The salt is derived from the address rather than random, because sign-in has to
     * reproduce it before the server has said anything — asking the server for an account's
     * salt is an account enumeration oracle. Deriving it from the address still keeps two
     * athletes who chose the same password from producing the same proof, and the Worker adds
     * a random per-user salt of its own on top.
     *
     * The consequence to remember: an account's address is part of its password derivation, so
     * changing one would invalidate the proof. `PATCH /api/auth/me` does not accept an address.
     */
    private const val SALT_PREFIX = "healthhub/password/v1\n"

    /** Must match `normalizeEmail` in the Worker and in `web/src/features/auth/prehash.ts`. */
    fun normalizeEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

    fun saltFor(email: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest((SALT_PREFIX + normalizeEmail(email)).toByteArray(Charsets.UTF_8))

    /**
     * Derives the proof the Worker stores a hash of.
     *
     * Roughly a second of CPU on a mid-range phone, which is the cost an attacker pays per
     * guess and the reason the sign-in button has a busy state. It runs on the default
     * dispatcher because a second on the main thread is a frozen screen.
     */
    suspend fun proof(email: String, password: String): PasswordProofDto =
        withContext(Dispatchers.Default) {
            PasswordProofDto(
                scheme = SCHEME,
                value = Base64.getEncoder()
                    .encodeToString(pbkdf2(password, saltFor(email), ITERATIONS)),
            )
        }

    /**
     * PBKDF2-HMAC-SHA-256, written out rather than obtained from `SecretKeyFactory`.
     *
     * This is not reinvention for its own sake. `PBEKeySpec` takes a `char[]`, and what the
     * providers do with it differs: the Bouncy Castle implementation Android has shipped
     * converts each character to a single byte, so any password outside ASCII derives a
     * different key from the one WebCrypto derives in a browser — and the athlete simply
     * cannot sign in on their other client, with nothing anywhere saying why. `Mac` has no
     * such ambiguity: it is keyed with the bytes it is given.
     *
     * The derived length equals the hash length, so RFC 2898 needs exactly one block, i = 1.
     */
    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        require(password.isNotEmpty()) { "password must not be empty" }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))

        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        // doFinal resets the Mac to its state just after init, so the key schedule is set up
        // once rather than 600,000 times.
        var u = mac.doFinal()
        val result = u.copyOf(KEY_BYTES)

        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) {
                result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
            }
        }
        return result
    }
}
