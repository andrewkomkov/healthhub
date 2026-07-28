package dev.healthhub.feature.updates

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A release newer than the one running, with everything needed to install it.
 *
 * `apkUrl` is nullable because a release can exist before its APK finishes uploading, and
 * because a fork may tag without building one. That case is not a failure — it degrades to
 * "open the release page", which is what the athlete would have done by hand anyway.
 */
data class AvailableUpdate(
    val version: String,
    val apkUrl: String?,
    val apkBytes: Long,
    val checksumUrl: String?,
    val pageUrl: String,
    val notes: String?,
)

/**
 * The shape of the releases API, reduced to the four fields that matter.
 *
 * `ignoreUnknownKeys` is not optional here: the real response carries about sixty fields per
 * release and GitHub adds more without warning.
 */
@Serializable
internal data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
internal data class AssetDto(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/**
 * Everything about a release that can be decided without a network or a device.
 *
 * Kept apart from [UpdateRepository] so the rules that actually go wrong — which tag counts as
 * newer, which asset is the APK — are unit-testable on the JVM.
 */
internal object Releases {

    val json = Json { ignoreUnknownKeys = true }

    /** Parses the releases payload, or throws if it is not a release at all. */
    fun parse(body: String): ReleaseDto = json.decodeFromString<ReleaseDto>(body)

    /**
     * The update this release represents, or null when it is not one.
     *
     * Drafts and pre-releases are ignored: `/releases/latest` already excludes them, but the
     * same function serves the "check this specific tag" path over ADB, which does not.
     */
    fun updateFrom(release: ReleaseDto, currentVersion: String): AvailableUpdate? {
        if (release.draft || release.prerelease) return null
        val version = release.tagName.removePrefix("v").trim()
        if (version.isEmpty()) return null
        if (compareVersions(version, currentVersion) <= 0) return null

        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
        val checksum = release.assets.firstOrNull { it.name.endsWith(".apk.sha256") }
        return AvailableUpdate(
            version = version,
            apkUrl = apk?.browserDownloadUrl?.takeIf { it.isNotEmpty() },
            apkBytes = apk?.size ?: 0,
            checksumUrl = checksum?.browserDownloadUrl?.takeIf { it.isNotEmpty() },
            pageUrl = release.htmlUrl.takeIf { it.isNotEmpty() }
                ?: "https://github.com/releases",
            notes = release.body?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Compares `1.10.0` against `1.9.3` by number rather than by string, which is the whole
     * reason this exists — lexically, `1.10.0` is the older one.
     *
     * A suffix on a segment (`1.2.0-rc1`) is truncated to its leading digits rather than
     * rejected: this app's own tags never carry one, and a release that does should still be
     * comparable instead of throwing on a phone.
     */
    fun compareVersions(left: String, right: String): Int {
        val a = segments(left)
        val b = segments(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = (a.getOrNull(i) ?: 0) - (b.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }

    private fun segments(version: String): List<Int> =
        version.removePrefix("v").split(".").map { part ->
            part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
        }

    /**
     * The digest out of a `sha256sum`-style file, or null if the file is not one.
     *
     * The published file is `<64 hex chars>  <filename>`; only the first field is ours, and a
     * file that does not look like that is treated as absent rather than as a mismatch —
     * refusing to install because a checksum file was malformed helps nobody.
     */
    fun parseChecksum(published: String): String? {
        val candidate = published.trim().substringBefore(' ').lowercase()
        val valid = candidate.length == 64 && candidate.all { it in '0'..'9' || it in 'a'..'f' }
        return candidate.takeIf { valid }
    }
}
