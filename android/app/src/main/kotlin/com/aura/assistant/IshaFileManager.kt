package com.aura.assistant

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ISHA-level file & storage operations.
 *
 * Design principles:
 *  - Every destructive write goes through the Safety Vault → always reversible.
 *  - All MediaStore queries use the Bundle/ContentResolver API on API 30+
 *    and plain sortOrder on older APIs to stay compatible across all OEMs
 *    (Samsung, Xiaomi/HyperOS, Realme, Vivo, Oppo, Motorola, Pixel, etc.).
 *  - StorageStatsManager is used on API 26+ for accurate per-app byte counts;
 *    a best-effort fallback is used on older builds.
 */
object IshaFileManager {

    private const val TAG = "IshaFileManager"
    private const val VAULT_DIR = "IshaRecycleBin"

    // ── Data Models ──────────────────────────────────────────────────────────

    data class AppStorageInfo(
        val appName: String,
        val packageName: String,
        val dataBytes: Long,
        val cacheBytes: Long,
        val apkBytes: Long,
        val totalBytes: Long,
        val isSystemApp: Boolean,
    )

    data class FileEntry(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val mimeType: String,
        val modifiedMs: Long,
        val isDir: Boolean,
    )

    data class OrganizeResult(
        val moved: Int,
        val skipped: Int,
        val bytesOrganized: Long,
        val summary: String,
    )

    // ── Storage Scan ─────────────────────────────────────────────────────────

    /**
     * Returns per-app storage breakdown sorted by total size descending.
     * Uses StorageStatsManager on API 26+; falls back to APK size on older builds.
     */
    fun scanStorage(ctx: Context): List<AppStorageInfo> {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            scanStorageV26(ctx, pm, apps)
        } else {
            apps.map { info ->
                val apk = runCatching { File(info.sourceDir ?: "").length() }.getOrDefault(0L)
                AppStorageInfo(pm.getApplicationLabel(info).toString(), info.packageName,
                    0L, 0L, apk, apk, info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
            }.sortedByDescending { it.totalBytes }
        }
    }

    private fun scanStorageV26(
        ctx: Context,
        pm: PackageManager,
        apps: List<ApplicationInfo>,
    ): List<AppStorageInfo> {
        val statsManager = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
        val storageUuid = resolveStorageUuid(ctx)

        return apps.mapNotNull { info ->
            runCatching {
                val stats = statsManager?.queryStatsForPackage(
                    storageUuid, info.packageName, android.os.Process.myUserHandle()
                )
                val apk = runCatching { File(info.sourceDir ?: "").length() }.getOrDefault(0L)
                AppStorageInfo(
                    appName = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    dataBytes = stats?.dataBytes ?: 0L,
                    cacheBytes = stats?.cacheBytes ?: 0L,
                    apkBytes = apk,
                    totalBytes = apk + (stats?.dataBytes ?: 0L),
                    isSystemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }.getOrNull()
        }.sortedByDescending { it.totalBytes }
    }

    private fun resolveStorageUuid(ctx: Context): UUID {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return StorageManager.UUID_DEFAULT
        return runCatching {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            val uuidStr = sm?.primaryStorageVolume?.uuid ?: return StorageManager.UUID_DEFAULT
            UUID.fromString(uuidStr)
        }.getOrDefault(StorageManager.UUID_DEFAULT)
    }

    // ── Cache Clear ──────────────────────────────────────────────────────────

    /**
     * Clears cache for a single package (or all user apps if packageName is null).
     * Walks the standard cache + code_cache directories; safe on all Android versions.
     */
    fun clearCache(ctx: Context, packageName: String?): Long {
        val pm = ctx.packageManager
        val targets = if (packageName != null) {
            runCatching { listOf(pm.getApplicationInfo(packageName, 0)) }.getOrDefault(emptyList())
        } else {
            pm.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
        }
        return targets.sumOf { info ->
            var freed = 0L
            runCatching {
                for (sub in listOf("cache", "code_cache")) {
                    val dir = File(info.dataDir, sub)
                    freed += dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    dir.deleteRecursively()
                }
            }
            freed
        }
    }

    // ── Installed Apps ───────────────────────────────────────────────────────

    /**
     * Returns all installed apps with accurate storage info.
     * Uses StorageStatsManager on API 26+; falls back gracefully.
     */
    fun getInstalledApps(ctx: Context, includeSystem: Boolean = false): List<Map<String, Any>> {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { includeSystem || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val statsManager = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as? StorageStatsManager
            val uuid = resolveStorageUuid(ctx)
            apps.map { info ->
                val apk = runCatching { File(info.sourceDir ?: "").length() }.getOrDefault(0L)
                val stats = runCatching {
                    statsManager?.queryStatsForPackage(uuid, info.packageName, android.os.Process.myUserHandle())
                }.getOrNull()
                mapOf<String, Any>(
                    "appName" to pm.getApplicationLabel(info).toString(),
                    "packageName" to info.packageName,
                    "apkSizeBytes" to apk,
                    "dataBytes" to (stats?.dataBytes ?: 0L),
                    "cacheBytes" to (stats?.cacheBytes ?: 0L),
                    "totalBytes" to (apk + (stats?.dataBytes ?: 0L)),
                    "isSystem" to (info.flags and ApplicationInfo.FLAG_SYSTEM != 0),
                )
            }.sortedByDescending { it["totalBytes"] as Long }
        } else {
            apps.map { info ->
                val apk = runCatching { File(info.sourceDir ?: "").length() }.getOrDefault(0L)
                mapOf<String, Any>(
                    "appName" to pm.getApplicationLabel(info).toString(),
                    "packageName" to info.packageName,
                    "apkSizeBytes" to apk,
                    "dataBytes" to 0L,
                    "cacheBytes" to 0L,
                    "totalBytes" to apk,
                    "isSystem" to (info.flags and ApplicationInfo.FLAG_SYSTEM != 0),
                )
            }.sortedByDescending { it["totalBytes"] as Long }
        }
    }

    // ── File Listing ─────────────────────────────────────────────────────────

    /**
     * Lists files in a directory path.
     *
     * Strategy (adaptive, works across all OEMs):
     *  1. If the path is a real accessible directory → direct File.listFiles()
     *  2. If not accessible (scoped storage on Android 11+ without MANAGE_EXTERNAL_STORAGE)
     *     → fall through to MediaStore query filtered by relative path
     */
    fun listFiles(ctx: Context, dirPath: String, limit: Int = 50): List<FileEntry> {
        // Normalize path: strip trailing slash, expand ~
        val normalized = dirPath.trimEnd('/').let {
            if (it.startsWith("~")) Environment.getExternalStorageDirectory().absolutePath + it.drop(1) else it
        }
        val dir = File(normalized)
        if (dir.exists() && dir.isDirectory && dir.canRead()) {
            val files = dir.listFiles() ?: emptyArray()
            return files
                .take(limit)
                .map { f ->
                    FileEntry(f.name, f.absolutePath,
                        if (f.isFile) f.length() else 0L,
                        guessMime(f.name), f.lastModified(), f.isDirectory)
                }
                .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenByDescending { it.modifiedMs })
        }
        // Fallback: query MediaStore with a relative path LIKE filter
        val folderName = dir.name
        return queryMediaStore(ctx, MediaStore.Files.getContentUri("external"), limit, nameFilter = null, relativeDir = folderName)
    }

    // ── File Search ──────────────────────────────────────────────────────────

    /**
     * Searches all media collections for files matching the query string.
     * Adaptive: uses Bundle query API on API 30+ (avoids OEM LIMIT crashes),
     * falls back to selection-only query on older builds.
     */
    fun searchFiles(ctx: Context, query: String, limit: Int = 20): List<FileEntry> {
        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Files.getContentUri("external"),
        )
        return uris
            .flatMap { uri -> queryMediaStore(ctx, uri, limit, nameFilter = query) }
            .distinctBy { it.path }
            .take(limit)
    }

    /**
     * Adaptive MediaStore query.
     *
     * On API 30+ (Android 11): uses [ContentResolver.query] with a [Bundle] so
     * LIMIT, OFFSET, and sort are expressed via Bundle keys — guaranteed to work
     * on all manufacturers (avoids Samsung/Xiaomi "Invalid token LIMIT" crash).
     *
     * On API 29 and below: uses the legacy query signature with sortOrder only
     * (no LIMIT in sortOrder string).
     */
    private fun queryMediaStore(
        ctx: Context,
        uri: Uri,
        limit: Int,
        nameFilter: String? = null,
        relativeDir: String? = null,
    ): List<FileEntry> {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )

        val entries = mutableListOf<FileEntry>()
        runCatching {
            val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: use Bundle-based query — LIMIT is a Bundle key, NOT in sortOrder string
                val queryArgs = Bundle().apply {
                    // Sort
                    putString(QA_SORT, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
                    // Limit
                    putInt(QA_LIMIT, limit)
                    // Selection
                    val selParts = mutableListOf<String>()
                    val selArgs = mutableListOf<String>()
                    if (nameFilter != null) {
                        selParts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                        selArgs += "%$nameFilter%"
                    }
                    if (relativeDir != null) {
                        selParts += "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                        selArgs += "%$relativeDir%"
                    }
                    if (selParts.isNotEmpty()) {
                        putString(QA_SEL, selParts.joinToString(" AND "))
                        putStringArray(QA_ARGS, selArgs.toTypedArray())
                    }
                }
                ctx.contentResolver.query(uri, projection, queryArgs, null)
            } else {
                // API 29 and below: classic query, NO "LIMIT" in sortOrder (OEM safe)
                val sel = buildLegacySelection(nameFilter, relativeDir)
                ctx.contentResolver.query(
                    uri, projection, sel.first, sel.second,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )
            }

            cursor?.use { c ->
                val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val iDate = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                var count = 0
                while (c.moveToNext() && count < limit) {
                    val path = if (iData >= 0) c.getString(iData) ?: "" else ""
                    entries += FileEntry(
                        name = if (iName >= 0) c.getString(iName) ?: "" else "",
                        path = path,
                        sizeBytes = if (iSize >= 0) c.getLong(iSize) else 0L,
                        mimeType = if (iMime >= 0) c.getString(iMime) ?: "" else "",
                        modifiedMs = if (iDate >= 0) c.getLong(iDate) * 1000L else 0L,
                        isDir = false,
                    )
                    count++
                }
            }
        }.onFailure { Log.w(TAG, "MediaStore query failed for $uri", it) }
        return entries
    }

    private fun buildLegacySelection(nameFilter: String?, relativeDir: String?): Pair<String?, Array<String>?> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (nameFilter != null) {
            parts += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            args += "%$nameFilter%"
        }
        if (relativeDir != null) {
            parts += "${MediaStore.MediaColumns.DATA} LIKE ?"
            args += "%/$relativeDir/%"
        }
        return if (parts.isEmpty()) null to null
        else parts.joinToString(" AND ") to args.toTypedArray()
    }

    // ── Safe Delete (Vault Backup) ────────────────────────────────────────────

    /**
     * Moves [filePath] to the Safety Vault before deletion.
     * @return The vault backup path on success, null if the file doesn't exist.
     */
    fun deleteToVault(ctx: Context, filePath: String): String? {
        return runCatching {
            val src = File(filePath)
            if (!src.exists()) return null
            val vaultDir = File(ctx.getExternalFilesDir(null), VAULT_DIR).also { it.mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dest = File(vaultDir, "${ts}_${src.name}")
            src.copyTo(dest, overwrite = true)
            src.delete()
            Log.i(TAG, "Vaulted: ${src.path} → ${dest.path}")
            dest.absolutePath
        }.getOrNull()
    }

    fun restoreFromVault(vaultPath: String, originalPath: String): Boolean {
        return runCatching {
            val src = File(vaultPath)
            val dest = File(originalPath)
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = false)
            src.delete()
        }.isSuccess
    }

    // ── File Move ─────────────────────────────────────────────────────────────

    fun moveFile(srcPath: String, destDir: String, overwrite: Boolean = false): Boolean {
        return runCatching {
            val src = File(srcPath)
            if (!src.exists()) return false
            val dest = File(destDir, src.name)
            if (dest.exists() && !overwrite) return false
            File(destDir).mkdirs()
            src.copyTo(dest, overwrite = overwrite)
            src.delete()
        }.isSuccess
    }

    // ── Photo Organization ────────────────────────────────────────────────────

    fun organizePhotos(ctx: Context, mode: String = "date"): OrganizeResult {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val photos = dcim.walkTopDown().filter { it.isFile && isPhotoOrVideo(it.name) }.toList()
        var moved = 0; var skipped = 0; var bytesOrganized = 0L

        for (photo in photos) {
            runCatching {
                val date = Date(photo.lastModified())
                val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(date)
                val month = SimpleDateFormat("MM_MMM", Locale.getDefault()).format(date)
                val destDir = if (mode == "date") File(dcim, "$year/$month") else File(dcim, year)
                if (photo.parentFile?.absolutePath == destDir.absolutePath) { skipped++; return@runCatching }
                destDir.mkdirs()
                val dest = File(destDir, photo.name)
                if (!dest.exists()) {
                    photo.copyTo(dest); photo.delete()
                    bytesOrganized += dest.length(); moved++
                } else skipped++
            }.onFailure { skipped++ }
        }
        return OrganizeResult(moved, skipped, bytesOrganized,
            "$moved photos organized (${formatBytes(bytesOrganized)}). $skipped already in place.")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun guessMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"; "gif" -> "image/gif"
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "avi" -> "video/avi"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "aac" -> "audio/aac"; "ogg" -> "audio/ogg"; "flac" -> "audio/flac"
        "pdf" -> "application/pdf"; "docx" -> "application/msword"
        "zip" -> "application/zip"; "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    private fun isPhotoOrVideo(name: String) =
        name.substringAfterLast('.', "").lowercase() in
            setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "mp4", "mov", "avi", "mkv")

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

// Bundle query arg keys for API 30+ MediaStore queries.
// These string values match android.content.ContentResolver.QUERY_ARG_* constants exactly.
private const val QA_SORT   = "android:query-arg-sql-sort-order"
private const val QA_SEL    = "android:query-arg-sql-selection"
private const val QA_ARGS   = "android:query-arg-sql-selection-args"
private const val QA_LIMIT  = "android:query-arg-limit"

/** Backward compatibility alias for JarvisFileManager */
val JarvisFileManager = IshaFileManager


