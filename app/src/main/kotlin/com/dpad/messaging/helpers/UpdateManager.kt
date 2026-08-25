package com.dpad.messaging.helpers

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.dpad.messaging.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val API_URL =
        "https://api.github.com/repos/jbriones95/DPAD-Messaging/releases/latest"
    private const val RELEASE_ASSET_NAME = "app-release.apk"
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

    suspend fun check(context: Context): Result {
        return try {
            val release = fetchRelease()
            val versionName = release.tagName.removePrefix("v")
            if (!isNewer(versionName, BuildConfig.VERSION_NAME)) {
                return Result.UpToDate
            }

            val asset = release.assets.firstOrNull { it.name == RELEASE_ASSET_NAME }
                ?: return Result.Error("The release APK was not found")
            if (!asset.downloadUrl.startsWith(RELEASE_ASSET_PREFIX)) {
                return Result.Error("The release APK URL is not trusted")
            }

            val apk = downloadApk(context, asset.downloadUrl, versionName)
            validateApk(context, apk)
            Result.Available(versionName, release.body.orEmpty(), apk)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Could not check for updates")
        }
    }

    private fun fetchRelease(): Release {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
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

    private fun downloadApk(context: Context, url: String, versionName: String): File {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(updateDir, "dpad-messaging-$versionName.apk")
        val temporary = File(updateDir, "$versionName.apk.part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DPAD-Messaging/${BuildConfig.VERSION_NAME}")
        }

        connection.useConnection { input ->
            if (input.responseCode !in 200..299) {
                throw IllegalStateException("Download failed with HTTP ${input.responseCode}")
            }
            temporary.outputStream().use { output -> input.inputStream.use { it.copyTo(output) } }
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
