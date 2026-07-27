package dev.healthhub.feature.sources

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The archive screen is the athlete's proof that nothing is ever destroyed, so the vocabulary
 * is part of the contract rather than a matter of taste.
 *
 * Asserted against the source itself rather than against a list of strings: a future card that
 * grows a destructive verb fails here before anyone ships it. The web client holds
 * `web/src/features/archive/` to the same words in `labels.test.ts`.
 */
class VocabularyTest {

    private val forbidden = listOf(
        Regex("""\bdelete""", RegexOption.IGNORE_CASE),
        Regex("""\bremove""", RegexOption.IGNORE_CASE),
        Regex("""\bdiscard""", RegexOption.IGNORE_CASE),
    )

    private val sources: List<File> = sourceRoot().walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.name }
        .toList()

    @Test
    fun `reads every file in the feature`() {
        assertThat(sources.map { it.name }).contains("ArchiveScreen.kt")
        assertThat(sources.size).isAtLeast(6)
    }

    @TestFactory
    fun `never speaks of destroying anything`(): List<DynamicTest> = sources.map { file ->
        DynamicTest.dynamicTest(file.name) {
            for (pattern in forbidden) {
                // `removeAt`/`removed` on a Kotlin list is mechanics, not a promise to the
                // athlete; only prose is under test.
                val prose = file.readText().replace(Regex("""\bremoveAt\b"""), "")
                assertThat(pattern.containsMatchIn(prose)).isFalse()
            }
        }
    }

    /**
     * Walked up from the working directory rather than resolved from a resource: Gradle runs
     * tests from the module directory, but a run from the repository root should find it too.
     */
    private fun sourceRoot(): File {
        val relative = "src/main/kotlin/dev/healthhub/feature/sources"
        var here: File? = File("").absoluteFile
        while (here != null) {
            File(here, relative).takeIf { it.isDirectory }?.let { return it }
            File(here, "android/feature/sources/$relative").takeIf { it.isDirectory }
                ?.let { return it }
            here = here.parentFile
        }
        error("Could not find $relative above ${File("").absolutePath}")
    }
}
