package com.aura.assistant.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.aura.assistant.BuildConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * High-performance In-App OTA Update Manager for Isha Assistant.
 * Checks GitHub Releases API for new APK releases, downloads updates in background,
 * and securely triggers the Android package installer via FileProvider.
 */
object IshaUpdateManager {
    private const val TAG = "IshaUpdateManager"
    private const val GITHUB_REPO = "archanakesharwani27-prog/ISHA-Personal-AI-Assistant-For-Android"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(
        val versionName: String,
        val releaseNotes: String,
        val apkDownloadUrl: String,
        val fileName: String,
        val isUpdateAvailable: Boolean
    )

    /**
     * Checks GitHub repository releases for a newer version than current BuildConfig.VERSION_NAME.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(RELEASES_API_URL)
                .header("User-Agent", "Isha-Android-Assistant/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "GitHub releases API returned code ${resp.code}")
                return@withContext null
            }

            val body = resp.body?.string() ?: return@withContext null
            val json = JsonParser.parseString(body).asJsonObject

            val rawTagName = json.get("tag_name")?.asString ?: ""
            val cleanTagName = rawTagName.removePrefix("v").trim()
            val releaseNotes = json.get("body")?.asString ?: "Bug fixes and performance improvements."

            var apkUrl = ""
            var fileName = "isha-$cleanTagName.apk"

            val assets = json.getAsJsonArray("assets")
            if (assets != null) {
                for (i in 0 until assets.size()) {
                    val asset = assets.get(i).asJsonObject
                    val name = asset.get("name")?.asString ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.get("browser_download_url")?.asString ?: ""
                        fileName = name
                        break
                    }
                }
            }

            if (apkUrl.isBlank()) {
                Log.w(TAG, "No APK asset found in latest GitHub release $cleanTagName")
                return@withContext null
            }

            val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v").trim()
            val isNewer = isVersionNewer(cleanTagName, currentVersion)

            Log.i(TAG, "Checked update: latest=$cleanTagName, current=$currentVersion, newer=$isNewer")
            return@withContext UpdateInfo(
                versionName = cleanTagName,
                releaseNotes = releaseNotes,
                apkDownloadUrl = apkUrl,
                fileName = fileName,
                isUpdateAvailable = isNewer
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            null
        }
    }

    /**
     * Downloads the APK file to external app files cache and prompts user to install.
     */
    suspend fun downloadAndInstall(
        context: Context,
        updateInfo: UpdateInfo,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val destinationDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.cacheDir
            val apkFile = File(destinationDir, updateInfo.fileName)

            Log.i(TAG, "Starting APK download from: ${updateInfo.apkDownloadUrl} to ${apkFile.absolutePath}")

            val req = Request.Builder()
                .url(updateInfo.apkDownloadUrl)
                .header("User-Agent", "Isha-Android-Assistant/${BuildConfig.VERSION_NAME}")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.e(TAG, "APK download failed with HTTP ${resp.code}")
                return@withContext false
            }

            val responseBody = resp.body ?: return@withContext false
            val contentLength = responseBody.contentLength()

            responseBody.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0 && onProgress != null) {
                            val progress = (totalRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) {
                                onProgress(progress)
                            }
                        }
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "APK download complete. Prompting install...")
            withContext(Dispatchers.Main) {
                triggerPackageInstaller(context, apkFile)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download and install failed: ${e.message}", e)
            false
        }
    }

    /**
     * Launches Android Package Installer using secure FileProvider URI.
     */
    fun triggerPackageInstaller(context: Context, apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
        }
    }

    /**
     * SemVer comparator (e.g. 2.2.0 > 2.1.0).
     */
    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
            val currentParts = current.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

            val maxParts = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxParts) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        } catch (_: Exception) {
            return latest != current
        }
    }
}
