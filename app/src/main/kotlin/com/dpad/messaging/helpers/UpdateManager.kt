package com.dpad.messaging.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dpad.messaging.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException

object UpdateManager {
    private const val RELEASES_API_URL =
        "https://api.github.com/repos/jbriones95/DPAD-Messaging/releases/latest"
    private const val EXPERIMENTAL_API_URL =
        "https://api.github.com/repos/jbriones95/DPAD-Messaging/releases/tags/experimental"
    private const val RELEASE_ASSET_NAME = "app-release.apk"
    private const val EXPERIMENTAL_ASSET_PREFIX = "app-experimental-"
    private const val RELEASE_ASSET_PREFIX =
        "https://github.com/jbriones95/DPAD-Messaging/releases/download/"

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data object UpToDate : Result
        data class Available(
            val versionName: String,
            val releaseNotes: String,
            val apk: File
        ) : Result
        data class Error(val message: String) : Result
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        val assets: List<Asset> = emptyList()
    )

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String
    )

    suspend fun check(
        context: Context,
        onDownloadProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result {
        return try {
            val experimental = BuildConfig.UPDATE_CHANNEL == "experimental" ||
                (BuildConfig.UPDATE_CHANNEL == "production" && Prefs.get().experimentalUpdates)
            val release = fetchRelease(experimental)
            val asset = if (experimental) {
                release.assets
                    .filter { it.name.startsWith(EXPERIMENTAL_ASSET_PREFIX) && it.name.endsWith(".apk") }
                    .maxByOrNull { experimentalVersionCode(it.name) }
            } else {
                release.assets.firstOrNull { it.name == RELEASE_ASSET_NAME }
            }
                ?: return Result.Error("The release APK was not found")
            if (!asset.downloadUrl.startsWith(RELEASE_ASSET_PREFIX)) {
                return Result.Error("The release APK URL is not trusted")
            }

            if (!experimental) {
                val versionName = release.tagName.removePrefix("v")
                if (!isNewer(versionName, BuildConfig.VERSION_NAME)) return Result.UpToDate
            } else if (experimentalVersionCode(asset.name) <= BuildConfig.VERSION_CODE) {
                return Result.UpToDate
            }

            val versionName = release.tagName.removePrefix("v")
            val apk = downloadApk(context, asset.downloadUrl, versionName, onDownloadProgress)
            validateApk(context, apk)
            Result.Available(versionName, release.body.orEmpty(), apk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e.message ?: "Could not check for updates")
        }
    }

    private fun fetchRelease(experimental: Boolean): Release {
        val apiUrl = if (experimental) EXPERIMENTAL_API_URL else RELEASES_API_URL
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "DPAD-Messaging/${BuildConfig.VERSION_NAME}")
        }
        return connection.useConnection { input ->
            if (input.responseCode !in 200..299) {
                throw IllegalStateException("GitHub returned HTTP ${input.responseCode}")
            }
            json.decodeFromString<Release>(input.inputStream.bufferedReader().readText())
        }
    }

    private fun experimentalVersionCode(assetName: String): Long {
        return Regex("^${EXPERIMENTAL_ASSET_PREFIX}(\\d+)\\.apk$")
            .matchEntire(assetName)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
    }

    private suspend fun downloadApk(
        context: Context,
        url: String,
        versionName: String,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(updateDir, "dpad-messaging-$versionName.apk")
        val temporary = File(updateDir, "$versionName.apk.part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DPAD-Messaging/${BuildConfig.VERSION_NAME}")
        }

        try {
            connection.useConnection { input ->
                if (input.responseCode !in 200..299) {
                    throw IllegalStateException("Download failed with HTTP ${input.responseCode}")
                }
                val totalBytes = input.contentLengthLong
                var downloadedBytes = 0L
                var lastReportedBytes = 0L
                temporary.outputStream().buffered().use { output ->
                    input.inputStream.buffered().use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloadedBytes += count
                            if (downloadedBytes - lastReportedBytes >= 512 * 1024 ||
                                (totalBytes > 0 && downloadedBytes >= totalBytes)
                            ) {
                                onProgress(downloadedBytes, totalBytes)
                                lastReportedBytes = downloadedBytes
                            }
                        }
                    }
                }
                onProgress(downloadedBytes, totalBytes)
            }
        } catch (e: Exception) {
            temporary.delete()
            throw e
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) throw IllegalStateException("Could not save the update")
        return target
    }

    private fun validateApk(context: Context, apk: File) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(apk.path, flags)
            ?: throw IllegalStateException("The downloaded file is not a valid APK")
        if (info.packageName != context.packageName) {
            throw IllegalStateException("The downloaded APK belongs to another app")
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        if (versionCode <= BuildConfig.VERSION_CODE) {
            throw IllegalStateException("The downloaded APK is not newer")
        }
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        val candidateParts = candidate.split('.').mapNotNull { it.toIntOrNull() }
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        if (candidateParts.isEmpty() || currentParts.isEmpty()) return false
        val size = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until size) {
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        return false
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }
}
