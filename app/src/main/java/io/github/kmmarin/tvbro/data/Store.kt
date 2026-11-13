// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Settings in plain prefs; sources (which hold credentials) in EncryptedSharedPreferences. */
class Store(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * True when the encrypted storage could not be opened or read (Keystore key missing or broken, e.g. data moved to
     * another device without its key): the sources must be added again. The app keeps running instead of crashing.
     */
    @Volatile var secureFailed = false
        private set
    @Volatile private var secureInst: android.content.SharedPreferences? = null

    private fun openSecure(): android.content.SharedPreferences = EncryptedSharedPreferences.create(
        ctx, "secure_sources",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Synchronized private fun secureOrNull(): android.content.SharedPreferences? = secureInst ?: try {
        openSecure().also { secureInst = it }
    } catch (e: Exception) {
        secureFailed = true; Diag.log("encrypted storage unavailable (${e.javaClass.simpleName})"); null
    }

    /**
     * For writing: the encrypted storage, recreated from scratch (new key, old unreadable file dropped) when it cannot
     * be opened; null only when the Keystore itself does not work.
     */
    @Synchronized private fun secureForWrite(): android.content.SharedPreferences? = secureOrNull() ?: retryOpen() ?: try {
        ctx.deleteSharedPreferences("secure_sources")
        runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS) }
        openSecure().also { secureInst = it; Diag.log("encrypted storage recreated") }
    } catch (e: Exception) { Diag.log("encrypted storage cannot be created (${e.javaClass.simpleName})"); null }

    /** A Keystore that is only slow or busy (just after boot) must not cost the stored sources: try again first. */
    private fun retryOpen(): android.content.SharedPreferences? {
        repeat(3) { Thread.sleep(400); try { return openSecure().also { secureInst = it; secureFailed = false } } catch (e: Exception) { } }
        return null
    }

    private fun readSecure(key: String, def: String): String? = try { (secureOrNull() ?: retryOpen())?.getString(key, def) } catch (e: Exception) {
        secureFailed = true; Diag.log("encrypted value unreadable (${e.javaClass.simpleName})"); null
    }

    private fun writeSecure(key: String, v: String) {
        val s = secureForWrite() ?: return
        try { s.edit { putString(key, v) } } catch (e: Exception) { Diag.log("encrypted write failed (${e.javaClass.simpleName})") }
    }

    fun sources(): List<Source> = try {
        Json.decodeFromString<List<Source>>(readSecure("sources", "[]") ?: "[]")
    } catch (e: Exception) { emptyList() }

    fun saveSources(list: List<Source>) { writeSecure("sources", Json.encodeToString(list)); hasSourcesHint = list.isNotEmpty() }

    /** Whether any source is stored (plain prefs, no credentials): the first frame opens on Add source without one. */
    var hasSourcesHint: Boolean?
        get() = if (sp.contains("hasSources")) sp.getBoolean("hasSources", false) else null
        set(v) { if (v != null && v != hasSourcesHint) sp.edit { putBoolean("hasSources", v) } }

    /** No encrypted sources file at all: a fresh install. */
    fun noSourceFile(): Boolean = !java.io.File(ctx.applicationInfo.dataDir, "shared_prefs/secure_sources.xml").exists()

    var epgHours: Int
        get() = sp.getInt("epgHours", 12)
        set(v) = sp.edit { putInt("epgHours", v) }
    var epgShiftMin: Int
        get() = sp.getInt("epgShiftMin", 0)
        set(v) = sp.edit { putInt("epgShiftMin", v) }
    var startLast: Boolean
        get() = sp.getBoolean("startLast", true)
        set(v) = sp.edit { putBoolean("startLast", v) }
    var bufferSec: Int
        get() = sp.getInt("bufferSec", 15)
        set(v) = sp.edit { putInt("bufferSec", v) }
    var lastKey: String?
        get() = sp.getString("lastKey", null)
        set(v) = sp.edit { putString("lastKey", v) }

    fun favourites(): Set<String> = HashSet(sp.getStringSet("favs", emptySet()) ?: emptySet())
    fun saveFavourites(s: Set<String>) = sp.edit { putStringSet("favs", HashSet(s)) }

    var recStorage: String
        get() = sp.getString("recStorage", "") ?: ""
        set(v) = sp.edit { putString("recStorage", v) }
    /** Network share for recordings (encrypted storage: holds the share password). */
    fun smb(): SmbConfig = try { Json.decodeFromString(readSecure("smb", "") ?: "") } catch (e: Exception) { SmbConfig() }
    fun saveSmb(c: SmbConfig) = writeSecure("smb", Json.encodeToString(c))
    var recPadStart: Int
        get() = sp.getInt("recPadStart", 1)
        set(v) = sp.edit { putInt("recPadStart", v) }
    var recPadEnd: Int
        get() = sp.getInt("recPadEnd", 3)
        set(v) = sp.edit { putInt("recPadEnd", v) }
    var recMinFreeMb: Long
        get() = sp.getLong("recMinFreeMb", 500L)
        set(v) = sp.edit { putLong("recMinFreeMb", v) }
    fun recordings(): List<Rec> = try { Json.decodeFromString<List<Rec>>(readSecure("recs", "[]") ?: "[]") } catch (e: Exception) { emptyList() }
    fun saveRecordings(l: List<Rec>) = writeSecure("recs", Json.encodeToString(l))

    fun epgMap(): Map<String, String> = try { Json.decodeFromString(sp.getString("epgmap", "{}") ?: "{}") } catch (e: Exception) { emptyMap() }
    fun saveEpgMap(m: Map<String, String>) = sp.edit { putString("epgmap", Json.encodeToString(m)) }

    fun prefs(): Prefs = try { Json { ignoreUnknownKeys = true }.decodeFromString(sp.getString("prefs", "") ?: "") } catch (e: Exception) { Prefs() }
    fun savePrefs(p: Prefs) = sp.edit { putString("prefs", Json.encodeToString(p)) }
    fun saveEpgMapAll(m: Map<String, String>) = saveEpgMap(m)

    var external: Boolean
        get() = sp.getBoolean("external", false)
        set(v) = sp.edit { putBoolean("external", v) }
    var matchFps: Boolean
        get() = sp.getBoolean("matchFps", true)
        set(v) = sp.edit { putBoolean("matchFps", v) }

    private val vp = ctx.getSharedPreferences("vod", Context.MODE_PRIVATE)
    /** Positions, durations and watched marks live in SQLite (state.db, see [WatchDb]). */
    val watch = WatchDb.get(ctx)
    fun pos(key: String): Long = watch.state(key)?.posMs ?: 0L
    fun setPos(key: String, ms: Long) = watch.setPos(key, ms)
    fun dur(key: String): Long = watch.state(key)?.durMs ?: 0L
    fun setDur(key: String, ms: Long) = watch.setDur(key, ms)
    fun watched(key: String): Boolean = watch.state(key)?.watched ?: false
    fun setWatched(key: String, b: Boolean) = watch.setWatched(key, b)
    /** Last UI state for a cold start: "screen|section|focused channel key|settings page|Movies/Shows category". */
    var lastUi: String
        get() = sp.getString("lastUi", "") ?: ""
        set(v) = sp.edit { putString("lastUi", v) }
    /** TimeFmt.H12 (default), H24 or SYSTEM. */
    var timeFormat: Int
        get() = sp.getInt("timeFormat", TimeFmt.H12)
        set(v) = sp.edit { putInt("timeFormat", v) }
    init { TimeFmt.mode = timeFormat.coerceIn(0, 2) } // also in the recording / reminder services
    var autoplayNext: Boolean
        get() = sp.getBoolean("autoplayNext", true)
        set(v) = sp.edit { putBoolean("autoplayNext", v) }
    fun recent(): List<String> = (vp.getString("recent", "") ?: "").split('\n').filter { it.isNotEmpty() }
    fun addRecent(key: String) = vp.edit { putString("recent", (listOf(key) + recent().filter { it != key }).take(30).joinToString("\n")) }

    var chHours: Int
        get() = sp.getInt("chHours", 12)
        set(v) = sp.edit { putInt("chHours", v) }
    var vodHours: Int
        get() = sp.getInt("vodHours", 24)
        set(v) = sp.edit { putInt("vodHours", v) }
    fun searchHistory(): List<String> = SearchHistory.decode(sp.getString("searchHistory", ""))
    fun saveSearchHistory(l: List<String>) = sp.edit { putString("searchHistory", SearchHistory.encode(l)) }
    var maxStreams: Int
        get() = sp.getInt("maxStreams", 1)
        set(v) = sp.edit { putInt("maxStreams", v) }
    fun lastRefresh(id: String): Long = sp.getLong("lr_$id", 0L)
    fun setLastRefresh(id: String, t: Long) = sp.edit { putLong("lr_$id", t) }

    private val snapJson = Json { ignoreUnknownKeys = true }
    private fun loadSnap(): MutableMap<String, VodItem> =
        try { snapJson.decodeFromString<Map<String, VodItem>>(vp.getString("snaps", "{}") ?: "{}").toMutableMap() } catch (e: Exception) { mutableMapOf() }
    /**
     * Snapshots of titles for My list / Continue watching (plain prefs). They never keep the stream address: a
     * playlist (M3U) title's address carries the login and is looked up in the decrypted playlist at play time.
     */
    fun snap(item: VodItem) {
        val m = loadSnap(); m[item.key] = item.copy(url = null)
        if (m.size > 300) { val keep = (recent() + favourites() + watch.allSeries().filter { it.active }.map { it.key }).toSet(); m.keys.retainAll { it in keep || it == item.key } }
        vp.edit { putString("snaps", snapJson.encodeToString(m)) }
    }
    fun snaps(keys: List<String>): List<VodItem> { val m = loadSnap(); return keys.mapNotNull { m[it] } }
    /** One-time migration: snapshots written by older versions lose their stream addresses. */
    fun stripSnapUrls() {
        val m = loadSnap()
        if (m.values.any { it.url != null }) vp.edit(commit = true) { putString("snaps", snapJson.encodeToString(m.mapValues { it.value.copy(url = null) })) }
    }
    var previewOn: Boolean
        get() = sp.getBoolean("previewOn", true)
        set(v) = sp.edit { putBoolean("previewOn", v) }
    var previewMuted: Boolean
        get() = sp.getBoolean("previewMuted", false) // sound on by default: picture and sound together
        set(v) = sp.edit { putBoolean("previewMuted", v) }

    var captionSize: Int
        get() = sp.getInt("captionSize", 1)
        set(v) = sp.edit { putInt("captionSize", v) }
    var captionBg: Int
        get() = sp.getInt("captionBg", 1)
        set(v) = sp.edit { putInt("captionBg", v) }

    fun audioDelay(key: String): Int? = if (sp.contains("ad_$key")) sp.getInt("ad_$key", 0) else null
    fun setAudioDelay(key: String, ms: Int) = sp.edit { putInt("ad_$key", ms) }
    var audioDelayDefault: Int
        get() = sp.getInt("audioDelayDefault", 0)
        set(v) = sp.edit { putInt("audioDelayDefault", v) }

    fun downloads(): List<Dl> = try { Json.decodeFromString<List<Dl>>(readSecure("dls", "[]") ?: "[]") } catch (e: Exception) { emptyList() }
    fun saveDownloads(l: List<Dl>) = writeSecure("dls", Json.encodeToString(l))

    var numberMode: Int
        get() = sp.getInt("numberMode", 0)
        set(v) = sp.edit { putInt("numberMode", v) }
    var lastGroup: String?
        get() = sp.getString("lastGroup", null)
        set(v) = sp.edit { putString("lastGroup", v) }
}