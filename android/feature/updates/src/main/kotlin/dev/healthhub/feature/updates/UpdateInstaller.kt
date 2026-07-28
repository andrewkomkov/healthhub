package dev.healthhub.feature.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetching an APK and handing it to the system.
 *
 * There is no store in this architecture, so there are no store updates: the only source is a
 * GitHub release. What keeps that safe is not this class — it is the platform. Android refuses
 * to install a package signed with a different key over one already installed, so an APK from
 * anywhere else cannot replace this app whatever this code does. The checksum below catches
 * the other failure: a download that was truncated or altered on its way here.
 */
@Singleton
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    @Named("appVersion") private val currentVersion: String,
) {
    /**
     * Downloads the release APK, reporting progress as it goes.
     *
     * `expectedBytes` comes from the release metadata and is only a guard: a body far larger
     * than the release says it is stops rather than filling the phone's cache.
     */
    suspend fun download(
        url: String,
        version: String,
        expectedBytes: Long,
        onProgress: (done: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // Previous attempts are not kept: an APK is tens of megabytes and exactly one is wanted.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "healthhub-$version.apk")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "healthhub/$currentVersion")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("the download answered ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: expectedBytes
            // Neither the release metadata nor the response has to state a length. When
            // neither does, the ceiling is the flat one: a cap that only ever trips on
            // something that is not our APK.
            val ceiling = maxOf(expectedBytes, total).takeIf { it > 0 }?.plus(SLACK_BYTES)
                ?: MAX_APK_BYTES
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done > ceiling) throw IOException("the download is larger than the release")
                        onProgress(done, total)
                    }
                }
            }
        }
        target
    }

    /**
     * Compares the file against the checksum published beside it.
     *
     * A release without one — every release built before the workflow published it — is not a
     * reason to refuse the install: the signature check the system performs is the one that
     * decides whether this APK may replace this app, and it happens either way.
     */
    suspend fun verify(apk: File, checksumUrl: String?): Boolean = withContext(Dispatchers.IO) {
        if (checksumUrl == null) return@withContext true
        val published = runCatching {
            val request = Request.Builder()
                .url(checksumUrl)
                .header("User-Agent", "healthhub/$currentVersion")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body.string()
            }
        }.getOrNull() ?: return@withContext true

        val expected = Releases.parseChecksum(published) ?: return@withContext true
        apk.inputStream().use { sha256(it) } == expected
    }

    /**
     * Opens an install session and commits the file into it.
     *
     * The session is committed with a callback rather than an activity result because the
     * confirmation belongs to the system: it comes back as
     * [PackageInstaller.STATUS_PENDING_USER_ACTION] carrying a ready-made dialog, and the
     * athlete sees the ordinary "update this app?" prompt.
     */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller
            .SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("healthhub", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION),
                // Mutable because the system fills the result in; without it the session
                // reports nothing back and the progress bar never ends.
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(callback.intentSender)
        }
    }

    fun clearCache() {
        File(context.cacheDir, "update").listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Release metadata is exact; the slack covers a proxy that re-encodes the body. */
        private const val SLACK_BYTES = 4L * 1024 * 1024

        /** Enough for any APK this project builds — the release one is about 50 MB. */
        private const val MAX_APK_BYTES = 300L * 1024 * 1024

        fun sha256(stream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
