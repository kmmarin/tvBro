// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import io.github.kmmarin.tvbro.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * "Update available" for sideloaded installs, from the GitHub Releases of the project repository.
 *
 * Privacy: only two kinds of HTTPS requests, both to GitHub: the Releases API (at most once a day at start,
 * or when the user presses "Check for updates") and the release asset downloads the user asked for. No
 * identifiers, no analytics. The automatic check can be turned off in Settings > About; builds made with
 * -Ptvbro.updateCheck=false (F-Droid) contain no check at all.
 *
 * Safety: the APK must come from this repository's release download URL, stay within the announced size,
 * match the SHA-256 published for the release (GitHub's asset digest or the release's SHA256SUMS.txt;
 * without one it is refused), be the same app, be newer, and be signed with the same certificate as the
 * installed copy. It is then handed to the system installer, where the user presses Install.
 */
object Updater {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val version: String) : State
        data class Available(val release: ReleaseInfo) : State
        data class Downloading(val release: ReleaseInfo, val percent: Int) : State
        data class Ready(val release: ReleaseInfo, val file: File) : State
        data class Failed(val message: String, val release: ReleaseInfo? = null) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    /** Set when a start-up check finds a version to offer; drives the in-app "Update available" dialog. */
    var prompt by mutableStateOf<ReleaseInfo?>(null)
        private set

    /** Latest version seen by the last check (for Settings > About). */
    var latestSeen by mutableStateOf<String?>(null)
        private set

    private const val PREFS = "updates"
    private const val DAY_MS = 20L * 60 * 60 * 1000
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followSslRedirects(false) // never follow an https -> http redirect
            .build()
    }

    /** False when this build has no update check (e.g. F-Droid builds, which F-Droid signs and updates itself). */
    val supported: Boolean get() = BuildConfig.UPDATE_CHECK && BuildConfig.UPDATE_REPO.contains('/')

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun autoEnabled(ctx: Context) = supported && prefs(ctx).getBoolean("auto", true)
    fun setAuto(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("auto", on).apply()

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    /** Called at app start: at most once a day when automatic checks are on; offers newer, not-skipped versions. */
    suspend fun checkIfDue(ctx: Context) {
        if (!autoEnabled(ctx)) return
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last", 0L) in 0 until DAY_MS) return
        p.edit().putLong("last", now).apply()
        val s = check(ctx)
        if (s is State.Available && UpdateLogic.shouldPrompt(s.release.version, currentVersion(ctx), p.getString("skip", null))) {
            prompt = s.release
        }
    }

    /** "Later": closes the dialog; the next daily check offers it again. */
    fun later() { prompt = null }

    /** "Skip this version": no more start-up offers for it (Settings > About still shows it). */
    fun skip(ctx: Context, rel: ReleaseInfo) {
        prefs(ctx).edit().putString("skip", rel.version).apply()
        prompt = null
    }

    /** Asks GitHub for the latest release and updates [state]. */
    suspend fun check(ctx: Context): State {
        if (!supported) return State.Failed("Update check is not part of this build").also { state = it }
        state = State.Checking
        val result = withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "tvBro-update-check")
                    .build()
                http.newCall(req).execute().use { r ->
                    when {
                        r.code == 404 -> State.Failed("No release published yet")
                        !r.isSuccessful -> State.Failed("GitHub answered ${r.code}")
                        else -> {
                            val rel = UpdateLogic.parseLatest(r.body?.string().orEmpty(), BuildConfig.UPDATE_REPO)
                            val cur = currentVersion(ctx)
                            latestSeen = rel?.version
                            when {
                                rel == null -> State.Failed("The latest release has no APK for this app")
                                UpdateLogic.isNewer(rel.version, cur) -> State.Available(rel)
                                else -> State.UpToDate(cur)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                State.Failed("Could not reach GitHub: ${e.javaClass.simpleName}")
            }
        }
        state = result
        return result
    }

    /** Downloads and verifies the APK of [rel]; on success the state is [State.Ready]. */
    suspend fun download(ctx: Context, rel: ReleaseInfo) {
        state = State.Downloading(rel, 0)
        state = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() } // only earlier update downloads live here
            val out = File(dir, UpdateLogic.fileName(rel))
            try {
                val expected = rel.sha256 ?: rel.sumsUrl?.let { fetchSums(it) }?.let { UpdateLogic.parseSums(it, rel.apkName) }
                    ?: return@withContext State.Failed("This release publishes no checksum, so it is not installed", rel)
                val md = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(rel.apkUrl).header("User-Agent", "tvBro-update-check").build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext State.Failed("Download failed (${r.code})", rel)
                    val body = r.body ?: return@withContext State.Failed("Download failed (empty)", rel)
                    val total = body.contentLength().takeIf { it > 0 } ?: rel.apkSize
                    val cap = UpdateLogic.maxDownloadBytes(rel)
                    if (total > cap) return@withContext State.Failed("The download is larger than expected", rel)
                    body.byteStream().use { inp ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024); var done = 0L; var lastPct = -1
                            while (true) {
                                val n = inp.read(buf); if (n < 0) break
                                o.write(buf, 0, n); md.update(buf, 0, n); done += n
                                if (done > cap) { o.close(); out.delete(); return@withContext State.Failed("The download is larger than expected", rel) }
                                if (total > 0) { val pct = (done * 100 / total).toInt().coerceAtMost(100); if (pct != lastPct) { lastPct = pct; state = State.Downloading(rel, pct) } }
                            }
                        }
                    }
                }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                verify(ctx, out, expected, hex)?.let { out.delete(); return@withContext State.Failed(it, rel) }
                State.Ready(rel, out)
            } catch (e: Exception) {
                out.delete()
                State.Failed("Download failed: ${e.javaClass.simpleName}", rel)
            }
        }
    }

    private fun fetchSums(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "tvBro-update-check").build()).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.byteStream()?.use { s -> String(s.readNBytesCompat(65_536)) }
        }
    } catch (e: Exception) { null }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
        while (out.size() < max) { val n = read(buf, 0, minOf(buf.size, max - out.size())); if (n < 0) break; out.write(buf, 0, n) }
        return out.toByteArray()
    }

    /** Null when the file is fine, else the reason it must not be installed. */
    private fun verify(ctx: Context, f: File, expectedSha256: String, actualSha256: String): String? {
        if (!UpdateLogic.hashOk(expectedSha256, actualSha256)) return "Checksum mismatch: the download was discarded"
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(f.path, flags) ?: return "The download is not a valid APK"
        if (apk.packageName != ctx.packageName) return "The download is a different app"
        val mine = try { pm.getPackageInfo(ctx.packageName, flags) } catch (e: Exception) { return "Cannot read this app's signature" }
        @Suppress("DEPRECATION")
        val newer = if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode > mine.longVersionCode else apk.versionCode > mine.versionCode
        if (!newer) return "The download is not newer than this version"
        if (!UpdateLogic.certsMatch(certs(mine), certs(apk))) {
            return "The release is signed with a different key than this copy. Uninstall tvBro, then install the release APK by hand."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun certs(p: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= 28) {
            val si = p.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else p.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return raw.orEmpty().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /**
     * Hands the verified APK to the system installer. When installing apps is not allowed yet, opens the right
     * settings page and explains the switch: Android 8+ per app ("Install unknown apps"), Android 7 / Fire OS 6
     * the global "Unknown sources" switch.
     */
    fun install(ctx: Context, rel: ReleaseInfo, f: File) {
        if (!canInstall(ctx)) {
            val (intent, hint) = if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)) to
                    "Allow \"Install unknown apps\" for tvBro, then come back and choose Install again."
            } else {
                Intent(Settings.ACTION_SECURITY_SETTINGS) to
                    "Turn on \"Unknown sources\" (Fire TV: Settings > My Fire TV > Developer options > Apps from Unknown Sources), then come back and choose Install again."
            }
            try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { /* hint below says where */ }
            readyHint = hint
            state = State.Ready(rel, f) // the download stays ready; Install again after allowing it
            return
        }
        readyHint = null
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) { state = State.Failed("No installer found on this device", rel) }
    }

    /** Shown next to a Ready download when installing is not allowed yet. */
    var readyHint by mutableStateOf<String?>(null)
        private set

    @Suppress("DEPRECATION")
    private fun canInstall(ctx: Context): Boolean = if (Build.VERSION.SDK_INT >= 26) {
        ctx.packageManager.canRequestPackageInstalls()
    } else {
        try { Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1 } catch (e: Exception) { true }
    }
}
