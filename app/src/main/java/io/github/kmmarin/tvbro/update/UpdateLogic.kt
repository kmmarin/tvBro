// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A published release that carries an installable APK. */
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 from the GitHub asset "digest" field, when GitHub provides it. */
    val sha256: String?,
    val notes: String,
    val pageUrl: String,
    /** The release's SHA256SUMS.txt asset (written by the release workflow), used when GitHub gives no digest. */
    val sumsUrl: String? = null,
)

/** Pure logic of the update check (no Android classes), unit-tested on the JVM. */
object UpdateLogic {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** "v1.2.3", "1.2", "1.2.3-beta1" -> [1, 2, 3]; null when it is not a version. */
    fun parse(v: String): List<Int>? {
        val core = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums + List(4 - nums.size) { 0 }
    }

    /** True when [candidate] is strictly newer than [current]. Unparseable versions are never newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return true
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /**
     * Parses the JSON of GET /repos/{owner}/{repo}/releases/latest (which already skips drafts and
     * pre-releases). Picks the APK asset: a non-debug ".apk", preferring one whose name contains "tvbro".
     */
    fun parseLatest(body: String, repo: String): ReleaseInfo? {
        val o = json.parseToJsonElement(body).jsonObject
        if (o.bool("draft") || o.bool("prerelease")) return null
        val tag = o.str("tag_name") ?: return null
        val version = tag.removePrefix("v").removePrefix("V")
        if (parse(version) == null) return null
        val assets = o["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            .filter { (it.str("name") ?: "").endsWith(".apk", ignoreCase = true) && !(it.str("name") ?: "").contains("debug", ignoreCase = true) }
        val apk = assets.firstOrNull { (it.str("name") ?: "").contains("tvbro", ignoreCase = true) } ?: assets.firstOrNull() ?: return null
        val url = apk.str("browser_download_url") ?: return null
        if (!isAllowedAssetUrl(url, repo)) return null
        val sums = o["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            .firstOrNull { (it.str("name") ?: "").equals("SHA256SUMS.txt", ignoreCase = true) }
            ?.str("browser_download_url")?.takeIf { isAllowedAssetUrl(it, repo) }
        val digest = apk.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
        return ReleaseInfo(
            version = version, tag = tag, apkName = apk.str("name")!!, apkUrl = url,
            apkSize = apk["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L,
            sha256 = digest?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } },
            notes = (o.str("body") ?: "").take(2000), pageUrl = o.str("html_url") ?: "", sumsUrl = sums,
        )
    }

    /** Only https://github.com/<owner>/<repo>/releases/download/... of this app's own repository. */
    fun isAllowedAssetUrl(url: String, repo: String): Boolean {
        if (!repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) return false
        val prefix = "https://github.com/$repo/releases/download/"
        return url.length > prefix.length && url.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true) &&
            !url.contains("..") && !url.contains('?') && !url.contains('#') && !url.contains('@')
    }

    /** Download cap: the size GitHub announces plus 1 MB, or 100 MB when it announces none. */
    fun maxDownloadBytes(r: ReleaseInfo): Long = if (r.apkSize > 0) r.apkSize + 1_048_576 else 100L * 1_048_576

    /** Offer [latest] at start only when it is newer and the user has not chosen "Skip this version" for it. */
    fun shouldPrompt(latest: String, current: String, skipped: String?): Boolean =
        isNewer(latest, current) && !latest.equals(skipped?.removePrefix("v") ?: "", ignoreCase = true)

    /** The SHA-256 for [fileName] from a sha256sum-style file ("<hex>  <name>" or "<hex> *<name>"). */
    fun parseSums(text: String, fileName: String): String? = text.lineSequence().mapNotNull { line ->
        val m = Regex("^([0-9a-fA-F]{64})\\s+\\*?(.+?)\\s*$").find(line) ?: return@mapNotNull null
        if (m.groupValues[2].substringAfterLast('/') == fileName) m.groupValues[1].lowercase() else null
    }.firstOrNull()

    /** A download may be installed only with a known expected hash that matches. */
    fun hashOk(expected: String?, actual: String): Boolean =
        expected != null && expected.length == 64 && expected.equals(actual, ignoreCase = true)

    /** Same signing certificates as the installed app (and at least one). */
    fun certsMatch(installed: Set<String>, download: Set<String>): Boolean = installed.isNotEmpty() && installed == download

    /** Safe file name for the downloaded APK inside the app cache. */
    fun fileName(r: ReleaseInfo): String = "tvbro-" + r.version.replace(Regex("[^0-9A-Za-z._-]"), "_") + ".apk"

    private fun JsonObject.str(k: String): String? = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.bool(k: String): Boolean = str(k) == "true"
}
