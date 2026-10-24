package com.hyperboost.engine.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sha256: String,
    val minimumSupportedVersion: String
)

sealed class CompatibilityResult {
    data object Compatible : CompatibilityResult()
    data class Incompatible(val installedCertSha256: String, val updateCertSha256: String) : CompatibilityResult()
    data object CannotVerify : CompatibilityResult()
}

object UpdateManager {
    private const val RELEASE_API = "https://api.github.com/repos/A-124/hyperboost-engine-pro/releases/latest"
    private const val POLICY_URL = "https://raw.githubusercontent.com/A-124/hyperboost-engine-pro/main/update.json"

    suspend fun fetchLatest(): UpdateInfo = withContext(Dispatchers.IO) {
        val release = JSONObject(readUrl(RELEASE_API))
        val version = release.optString("tag_name").removePrefix("v")
        require(version.matches(Regex("""\d+\.\d+\.\d+([-.][A-Za-z0-9.-]+)?"""))) { "The latest release version is invalid." }
        val assets = release.optJSONArray("assets") ?: error("The latest release has no assets.")
        var apkUrl = ""
        var digest = ""
        var checksumUrl = ""

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name == "HyperBoost-Engine-Pro-$version.apk" || name == "HyperBoost-Engine-Pro-v$version.apk") {
                apkUrl = asset.optString("browser_download_url")
                digest = asset.optString("digest").removePrefix("sha256:").lowercase()
            } else if (name == "SHA256SUMS.txt") {
                checksumUrl = asset.optString("browser_download_url")
            }
        }

        // Fallback: If GitHub API asset metadata did not include SHA-256 digest, parse SHA256SUMS.txt
        if (!digest.matches(Regex("[a-f0-9]{64}")) && checksumUrl.isNotBlank()) {
            try {
                val sums = readUrl(checksumUrl)
                val targetApkName = "HyperBoost-Engine-Pro-$version.apk"
                val line = sums.lines().firstOrNull { it.contains(targetApkName) }
                if (line != null) {
                    val parsedHash = line.trim().split(Regex("""\s+""")).firstOrNull().orEmpty().lowercase()
                    if (parsedHash.matches(Regex("[a-f0-9]{64}"))) {
                        digest = parsedHash
                    }
                }
            } catch (_: Exception) {
                // Ignore fallback parse error; validation below will handle invalid digest
            }
        }

        require(apkUrl.startsWith("https://github.com/") && digest.matches(Regex("[a-f0-9]{64}"))) {
            "The latest release is missing its APK or SHA-256 digest."
        }
        val minimum = try { JSONObject(readUrl(POLICY_URL)).optString("minimumSupportedVersion", "1.0.0") } catch (_: Exception) { "1.0.0" }
        UpdateInfo(version, release.optString("body", "").trim(), apkUrl, digest, minimum)
    }

    fun compareVersions(left: String, right: String): Int {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(left); val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return when {
            left.contains('-') && !right.contains('-') -> -1
            !left.contains('-') && right.contains('-') -> 1
            else -> 0
        }
    }

    /**
     * Downloads the APK while computing SHA-256 checksum on the fly to avoid out-of-memory errors.
     * Reports download progress if [onProgress] callback is supplied.
     */
    suspend fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: ((bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Clean up previously cached APKs to free storage
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".apk")) {
                file.delete()
            }
        }

        val apk = File(dir, "HyperBoost-Engine-Pro-${info.version}.apk")
        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HyperBoost-Engine-Pro")
        }

        val totalBytes = connection.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesDownloaded = 0L
        val buffer = ByteArray(8192)

        connection.inputStream.use { input ->
            FileOutputStream(apk).use { output ->
                var read: Int
                var lastProgressUpdate = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    bytesDownloaded += read

                    if (onProgress != null && totalBytes > 0) {
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 100 || bytesDownloaded == totalBytes) {
                            lastProgressUpdate = now
                            val percent = ((bytesDownloaded * 100) / totalBytes).toInt()
                            withContext(Dispatchers.Main) {
                                onProgress(bytesDownloaded, totalBytes, percent)
                            }
                        }
                    }
                }
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(info.sha256, true)) {
            apk.delete()
            error("APK checksum verification failed. The file was not installed.")
        }
        apk
    }

    /**
     * Checks if the downloaded APK is signed with the same certificate as the installed app.
     * Prevents Android's "App not installed as package conflicts with an existing package" error.
     */
    fun checkSignatureCompatibility(context: Context, apkFile: File): CompatibilityResult {
        return try {
            val pm = context.packageManager
            val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
            } ?: return CompatibilityResult.CannotVerify

            val installedInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                }
            } catch (_: Exception) {
                return CompatibilityResult.Compatible
            }

            fun sha256Hex(bytes: ByteArray): String {
                return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }

            val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installedInfo.signingInfo?.apkContentsSigners?.map { sha256Hex(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                installedInfo.signatures?.map { sha256Hex(it.toByteArray()) }
            } ?: emptyList()

            val updateSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.signingInfo?.apkContentsSigners?.map { sha256Hex(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.signatures?.map { sha256Hex(it.toByteArray()) }
            } ?: emptyList()

            if (installedSignatures.isNotEmpty() && updateSignatures.isNotEmpty()) {
                val matches = installedSignatures.any { updateSignatures.contains(it) }
                if (!matches) {
                    return CompatibilityResult.Incompatible(
                        installedCertSha256 = installedSignatures.firstOrNull() ?: "",
                        updateCertSha256 = updateSignatures.firstOrNull() ?: ""
                    )
                }
            }
            CompatibilityResult.Compatible
        } catch (_: Exception) {
            CompatibilityResult.CannotVerify
        }
    }

    /**
     * Sends the APK through PackageInstaller so Android reports the actual install result
     * back to the app instead of leaving the updater blind after opening an APK URI.
     */
    fun launchInstallIntent(context: Context, apk: File) {
        require(apk.isFile && apk.length() > 0L) { "The downloaded update APK is missing or empty." }

        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0L, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                    .putExtra(UpdateInstallReceiver.EXTRA_VERSION, apk.name)
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pendingIntent.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    /**
     * Copies the APK to the public Downloads folder so it persists even if the user
     * uninstalls the currently installed build to resolve a signature conflict.
     */
    fun copyApkToDownloads(context: Context, apk: File, version: String): File? {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val target = File(downloadsDir, "HyperBoost-Engine-Pro-$version.apk")
            apk.copyTo(target, overwrite = true)
            target
        } catch (_: Exception) {
            null
        }
    }

    private fun readUrl(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000; connection.readTimeout = 20000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "HyperBoost-Engine-Pro")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val apiMessage = runCatching { JSONObject(body).optString("message") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.take(180)
                error("Update server returned HTTP $code" + (apiMessage?.let { ": $it" } ?: "."))
            }
            body
        } finally { connection.disconnect() }
    }
}
