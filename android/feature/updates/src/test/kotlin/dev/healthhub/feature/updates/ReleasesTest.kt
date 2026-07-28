package dev.healthhub.feature.updates

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ReleasesTest {

    @Test
    fun `orders versions by number, not by string`() {
        // The reason this function exists: lexically, "1.10.0" sorts before "1.9.3".
        assertThat(Releases.compareVersions("1.10.0", "1.9.3")).isGreaterThan(0)
        assertThat(Releases.compareVersions("0.2.0", "0.10.0")).isLessThan(0)
        assertThat(Releases.compareVersions("1.0.0", "1.0.0")).isEqualTo(0)
        assertThat(Releases.compareVersions("v1.2.0", "1.2.0")).isEqualTo(0)
    }

    @Test
    fun `treats a missing segment as zero`() {
        assertThat(Releases.compareVersions("1.2", "1.2.0")).isEqualTo(0)
        assertThat(Releases.compareVersions("1.2.1", "1.2")).isGreaterThan(0)
    }

    @Test
    fun `compares a suffixed segment rather than throwing on it`() {
        assertThat(Releases.compareVersions("1.3.0-rc1", "1.2.9")).isGreaterThan(0)
    }

    @Test
    fun `finds the apk and its checksum whatever order the assets arrive in`() {
        val release = Releases.parse(RELEASE_JSON)
        val update = Releases.updateFrom(release, currentVersion = "0.1.0")

        assertThat(update).isNotNull()
        assertThat(update!!.version).isEqualTo("0.2.0")
        assertThat(update.apkUrl).endsWith("healthhub-0.2.0.apk")
        assertThat(update.apkBytes).isEqualTo(54_000_000L)
        assertThat(update.checksumUrl).endsWith("healthhub-0.2.0.apk.sha256")
        assertThat(update.notes).contains("Faster feed")
    }

    @Test
    fun `does not mistake the checksum file for the apk`() {
        // `.apk.sha256` does not end in `.apk`, but a naive `contains` would take it — and
        // the installer would then hand 64 bytes of text to the package installer.
        val release = Releases.parse(CHECKSUM_FIRST_JSON)
        val update = Releases.updateFrom(release, currentVersion = "0.1.0")

        assertThat(update!!.apkUrl).endsWith("healthhub-0.2.0.apk")
        assertThat(update.checksumUrl).endsWith("healthhub-0.2.0.apk.sha256")
    }

    @Test
    fun `reports nothing when the release is the running version or older`() {
        val release = Releases.parse(RELEASE_JSON)
        assertThat(Releases.updateFrom(release, currentVersion = "0.2.0")).isNull()
        assertThat(Releases.updateFrom(release, currentVersion = "0.3.1")).isNull()
    }

    @Test
    fun `ignores drafts and pre-releases`() {
        val draft = Releases.parse("""{"tag_name":"v9.0.0","draft":true}""")
        val pre = Releases.parse("""{"tag_name":"v9.0.0","prerelease":true}""")

        assertThat(Releases.updateFrom(draft, "0.1.0")).isNull()
        assertThat(Releases.updateFrom(pre, "0.1.0")).isNull()
    }

    @Test
    fun `survives a release with no assets at all`() {
        val release = Releases.parse("""{"tag_name":"v0.2.0","html_url":"https://x/rel"}""")
        val update = Releases.updateFrom(release, "0.1.0")

        // Offered, but with nothing to install: the screen degrades to the release page.
        assertThat(update).isNotNull()
        assertThat(update!!.apkUrl).isNull()
        assertThat(update.pageUrl).isEqualTo("https://x/rel")
    }

    @Test
    fun `ignores the fields GitHub adds without warning`() {
        // Sixty-odd fields come back per release; a strict parser would fail on the next one.
        val release = Releases.parse(
            """{"tag_name":"v0.2.0","invented_last_tuesday":{"nested":[1,2]}}""",
        )
        assertThat(release.tagName).isEqualTo("v0.2.0")
    }

    @Test
    fun `takes the digest out of a sha256sum file and rejects anything else`() {
        val digest = "a".repeat(64)
        assertThat(Releases.parseChecksum("$digest  healthhub-0.2.0.apk")).isEqualTo(digest)
        assertThat(Releases.parseChecksum("  $digest\n")).isEqualTo(digest)
        assertThat(Releases.parseChecksum(digest.uppercase())).isEqualTo(digest)

        assertThat(Releases.parseChecksum("<html>rate limited</html>")).isNull()
        assertThat(Releases.parseChecksum("z".repeat(64))).isNull()
        assertThat(Releases.parseChecksum("")).isNull()
    }

    private companion object {
        const val RELEASE_JSON = """
        {
          "tag_name": "v0.2.0",
          "html_url": "https://github.com/andrewkomkov/healthhub/releases/tag/v0.2.0",
          "name": "HealthHub 0.2.0",
          "body": "Faster feed and a route backfill.",
          "draft": false,
          "prerelease": false,
          "assets": [
            {
              "name": "healthhub-0.2.0.apk.sha256",
              "size": 84,
              "browser_download_url": "https://github.com/a/healthhub-0.2.0.apk.sha256"
            },
            {
              "name": "healthhub-0.2.0.apk",
              "size": 54000000,
              "browser_download_url": "https://github.com/a/healthhub-0.2.0.apk"
            }
          ]
        }
        """

        const val CHECKSUM_FIRST_JSON = """
        {
          "tag_name": "v0.2.0",
          "assets": [
            {
              "name": "healthhub-0.2.0.apk.sha256",
              "size": 84,
              "browser_download_url": "https://x/healthhub-0.2.0.apk.sha256"
            },
            {
              "name": "healthhub-0.2.0.apk",
              "size": 5,
              "browser_download_url": "https://x/healthhub-0.2.0.apk"
            }
          ]
        }
        """
    }
}
