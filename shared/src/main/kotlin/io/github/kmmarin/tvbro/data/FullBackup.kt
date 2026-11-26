// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** One saved setting with its type (SharedPreferences tell Int from Long). t: s, i, l, b, f or ss (string set). */
@Serializable
data class PrefValue(
    val t: String, val s: String? = null, val n: Long? = null, val b: Boolean? = null, val f: Float? = null,
    val ss: List<String>? = null,
) {
    companion object {
        fun of(v: Any?): PrefValue? = when (v) {
            is String -> PrefValue("s", s = v)
            is Int -> PrefValue("i", n = v.toLong())
            is Long -> PrefValue("l", n = v)
            is Boolean -> PrefValue("b", b = v)
            is Float -> PrefValue("f", f = v)
            is Set<*> -> PrefValue("ss", ss = v.filterIsInstance<String>().sorted())
            else -> null
        }
    }
}

/** A watch-state row of state.db (position, duration, watched mark of one movie / episode). */
@Serializable
data class WatchRowB(val key: String, val series: String? = null, val pos: Long = 0, val dur: Long = 0, val watched: Boolean = false, val updated: Long = 0)

/** A per-series row of state.db. [subs]: null = no choice, 0 off, 1 on. */
@Serializable
data class SeriesRowB(
    val key: String, val updated: Long = 0, val lastEp: String? = null, val nextEp: String? = null, val nextSeason: Int = 0,
    val nextNum: Int = 0, val nextTitle: String? = null, val finished: Boolean = false, val active: Boolean = false,
    val subs: Int? = null, val subLang: String? = null, val audioLang: String? = null,
)

/**
 * Everything a user would miss on a new device or after reinstalling: the saved settings files ([prefs]: file name ->
 * key -> value), the encrypted values ([secure]: sources with their logins, network share, recordings, downloads,
 * stream limits; raw JSON text) and the watch state of state.db. Caches (playlists, guide, search index) are not in it:
 * they are downloaded again.
 */
@Serializable
data class BackupBundle(
    val format: String = FullBackup.FORMAT,
    val version: Int = 1,
    val app: String = "",
    val pkg: String = "",
    val created: Long = 0,
    val prefs: Map<String, Map<String, PrefValue>> = emptyMap(),
    val secure: Map<String, String> = emptyMap(),
    val watch: List<WatchRowB> = emptyList(),
    val series: List<SeriesRowB> = emptyList(),
)

class BackupException(msg: String) : Exception(msg)

/**
 * The encrypted backup file (".tvbro"): "TVBK" | version (1) | PBKDF2 iterations (4) | salt (16) | IV (12) |
 * AES-256-GCM of the gzipped JSON [BackupBundle], with the 37-byte header as associated data. The key is
 * PBKDF2-HMAC-SHA256 of the passphrase ([Pin.pbkdf2]; javax's PBKDF2WithHmacSHA256 is missing on Android 7).
 * A wrong passphrase and a damaged file fail the same GCM check.
 */
object FullBackup {
    const val FORMAT = "tvbro-backup"
    const val EXT = ".tvbro"
    const val ITERATIONS = 200_000
    const val MIN_PASS = 8
    private val MAGIC = "TVBK".toByteArray()
    private const val HEADER = 4 + 1 + 4 + 16 + 12
    private const val MAX_FILE = 16 shl 20
    /** Unpacked size limit (a crafted file cannot fill a TV's memory; real backups are well below 1 MB). */
    private const val MAX_JSON = 32 shl 20
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val rnd = SecureRandom()

    /** The settings files in a backup. Updater and sync status stay per device. */
    val PREF_FILES = listOf("settings", "vod", "reminders", "seriesrules")
    /** Encrypted keys in a backup. */
    val SECURE_KEYS = listOf("sources", "smb", "recs", "dls", "streamLimits")

    /** Settings that describe this device's copy of the data, not the user's choices. */
    fun skipSetting(file: String, key: String): Boolean = when (file) {
        "settings" -> key == "atRest" || key == "hasSources" || key.startsWith("lr_")
        "vod" -> key.length > 2 && key[1] == '_' && key[0] in "pdw" // titles' state of old versions, already in state.db
        else -> false
    }

    fun isBackup(head: ByteArray): Boolean = head.size >= 4 && head.copyOf(4).contentEquals(MAGIC)

    fun encode(b: BackupBundle): ByteArray = json.encodeToString(b).toByteArray(Charsets.UTF_8)
    fun decode(b: ByteArray): BackupBundle = try {
        json.decodeFromString<BackupBundle>(String(b, Charsets.UTF_8)).also { if (it.format != FORMAT) throw BackupException("not a tvBro backup") }
    } catch (e: BackupException) { throw e } catch (e: Exception) { throw BackupException("the backup's contents cannot be read") }

    fun seal(b: BackupBundle, pass: String, iterations: Int = ITERATIONS): ByteArray {
        require(pass.length >= MIN_PASS) { "passphrase too short" }
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(1).putInt(iterations).put(salt).put(iv).array()
        val plain = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(encode(b)) } }.toByteArray()
        return header + cipher(Cipher.ENCRYPT_MODE, pass, salt, iterations, iv, header).doFinal(plain)
    }

    fun open(file: ByteArray, pass: String): BackupBundle {
        if (file.size > MAX_FILE) throw BackupException("file too large for a backup")
        if (file.size < HEADER + 16 || !isBackup(file)) throw BackupException("not a tvBro backup file")
        val bb = ByteBuffer.wrap(file)
        bb.position(4)
        if (bb.get().toInt() != 1) throw BackupException("backup from a newer tvBro: update first")
        val it = bb.int
        if (it !in 10_000..10_000_000) throw BackupException("not a tvBro backup file")
        val salt = ByteArray(16).also { s -> bb.get(s) }
        val iv = ByteArray(12).also { v -> bb.get(v) }
        val header = file.copyOf(HEADER)
        val plain = try { cipher(Cipher.DECRYPT_MODE, pass, salt, it, iv, header).doFinal(file, HEADER, file.size - HEADER) }
            catch (e: Exception) { throw BackupException("wrong passphrase, or the file is damaged") }
        val raw = try { GZIPInputStream(ByteArrayInputStream(plain)).use { g -> readCapped(g) } } catch (e: BackupException) { throw e } catch (e: Exception) { throw BackupException("the backup's contents cannot be read") }
        return decode(raw)
    }

    /** Reads a backup file, refusing anything larger than a backup can be. */
    fun readFile(i: java.io.InputStream): ByteArray {
        val o = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); if (o.size() > MAX_FILE) throw BackupException("file too large for a backup") }
        return o.toByteArray()
    }

    private fun readCapped(i: java.io.InputStream): ByteArray {
        val o = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024)
        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); if (o.size() > MAX_JSON) throw BackupException("backup too large") }
        return o.toByteArray()
    }

    private fun cipher(mode: Int, pass: String, salt: ByteArray, iterations: Int, iv: ByteArray, aad: ByteArray): Cipher {
        if (pass.isEmpty()) throw BackupException("no passphrase")
        val key = Pin.pbkdf2(pass.toByteArray(Charsets.UTF_8), salt, iterations, 32)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad) }
    }

    // ---- merging into what this device already has ----

    /** Paths into another build's app folder ("/Android/data/<package>/") point into this app's folder. */
    fun rehome(path: String, pkg: String): String = path.replace(Regex("/Android/data/[A-Za-z0-9_.]+/"), "/Android/data/$pkg/")

    /**
     * The values to write for one settings file: the backup's settings replace this device's, except the lists and
     * collections, which are merged (favourites, organisation, guide mapping, history, reminders...). Keys that only
     * describe this device's data ([skipSetting]) are left out.
     */
    fun mergePrefs(file: String, cur: Map<String, PrefValue>, inc: Map<String, PrefValue>, pkg: String): Map<String, PrefValue> {
        val out = LinkedHashMap<String, PrefValue>()
        for ((k, v) in inc) {
            if (skipSetting(file, k)) continue
            val c = cur[k]
            val m = when {
                file == "settings" && k == "favs" -> PrefValue("ss", ss = ((c?.ss ?: emptyList()) + (v.ss ?: emptyList())).distinct().sorted())
                file == "settings" && k == "prefs" -> v.s?.let { PrefValue("s", s = Json.encodeToString(mergeOrg(org(c?.s), org(it) ?: Prefs()))) }
                file == "settings" && k == "epgmap" -> PrefValue("s", s = mergeMaps(c?.s, v.s))
                file == "settings" && k == "recStorage" -> v.s?.let { PrefValue("s", s = rehome(it, pkg)) }
                file == "settings" && (k == "recentCh" || k == "searchHistory" || k == "lastKey" || k == "lastUi" || k == "lastGroup") -> if (c?.s.isNullOrEmpty()) v else null
                file == "vod" && k == "recent" -> PrefValue("s", s = ((c?.s ?: "").split('\n') + (v.s ?: "").split('\n')).filter { it.isNotEmpty() }.distinct().take(30).joinToString("\n"))
                file == "vod" && k == "snaps" -> PrefValue("s", s = mergeMaps(c?.s, v.s))
                (file == "reminders" || file == "seriesrules") && k == "l" -> PrefValue("s", s = mergeById(c?.s, v.s, incWins = false, pkg = null))
                file == "settings" || c == null -> v
                else -> null
            }
            if (m != null && m != c) out[k] = m
        }
        return out
    }

    /** The encrypted values to write: sources are matched by id (the backup's login wins), the rest merged. */
    fun mergeSecure(cur: Map<String, String>, inc: Map<String, String>, pkg: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in inc) {
            if (k !in SECURE_KEYS) continue
            val c = cur[k]
            val m = when (k) {
                "sources" -> mergeById(c, v, incWins = true, pkg = null)
                "recs", "dls" -> mergeById(c, v, incWins = false, pkg = pkg)
                "streamLimits" -> mergeMaps(c, v)
                "smb" -> if (c.isNullOrEmpty() || hostOf(c).isEmpty()) v else null
                else -> null
            }
            if (m != null && m != c) out[k] = m
        }
        return out
    }

    private fun hostOf(smb: String): String = try { ((Json.parseToJsonElement(smb) as? JsonObject)?.get("host") as? JsonPrimitive)?.content ?: "" } catch (e: Exception) { "" }

    private fun org(s: String?): Prefs? = if (s.isNullOrEmpty()) null else try { json.decodeFromString<Prefs>(s) } catch (e: Exception) { null }

    /**
     * Channel layout and locks: this device's choices stay and the backup's are added. The backup's PIN comes along
     * only when this device has none (a PIN set here is never replaced by an import); without any PIN no lock is kept.
     */
    fun mergeOrg(cur: Prefs?, inc: Prefs): Prefs {
        if (cur == null || cur == Prefs()) return if (inc.pinHash.isEmpty()) inc.copy(locked = emptySet(), lockedCh = emptySet()) else inc
        val pin = cur.pinHash.ifEmpty { inc.pinHash }
        return cur.copy(
            hiddenCh = cur.hiddenCh + inc.hiddenCh, names = inc.names + cur.names, order = cur.order.ifEmpty { inc.order },
            hiddenGroups = cur.hiddenGroups + inc.hiddenGroups, groupNames = inc.groupNames + cur.groupNames,
            groupOrder = cur.groupOrder.ifEmpty { inc.groupOrder }, custom = inc.custom + cur.custom, pinHash = pin,
            locked = if (pin.isEmpty()) emptySet() else cur.locked + inc.locked,
            lockedCh = if (pin.isEmpty()) emptySet() else cur.lockedCh + inc.lockedCh,
        )
    }

    /** Two JSON objects: this device's entries win. */
    fun mergeMaps(cur: String?, inc: String?): String {
        val a = obj(cur); val b = obj(inc)
        return JsonObject(b + a).toString()
    }

    /**
     * Two JSON arrays of objects with an "id": entries of both, in this device's order then the new ones. With
     * [incWins] an entry of the backup replaces this device's entry with the same id. [pkg]: "path" fields are moved
     * into this app's folder ([rehome]).
     */
    fun mergeById(cur: String?, inc: String?, incWins: Boolean, pkg: String?): String {
        fun fix(e: JsonElement): JsonElement {
            if (pkg == null || e !is JsonObject) return e
            val p = (e["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return e
            return JsonObject(e + ("path" to JsonPrimitive(rehome(p, pkg))))
        }
        fun id(e: JsonElement) = ((e as? JsonObject)?.get("id") as? JsonPrimitive)?.content
        val a = arr(cur); val b = arr(inc).map(::fix)
        val bById = b.associateBy(::id)
        val out = a.map { e -> if (incWins) id(e)?.let { bById[it] } ?: e else e }.toMutableList()
        val have = a.mapNotNull(::id).toSet()
        out += b.filter { val i = id(it); i == null || i !in have }
        return JsonArray(out).toString()
    }

    private fun obj(s: String?): Map<String, JsonElement> = if (s.isNullOrEmpty()) emptyMap() else try { Json.parseToJsonElement(s) as? JsonObject ?: emptyMap() } catch (e: Exception) { emptyMap() }
    private fun arr(s: String?): List<JsonElement> = if (s.isNullOrEmpty()) emptyList() else try { (Json.parseToJsonElement(s) as? JsonArray)?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }

    /** What a backup holds, for the confirmation and the summary (no names of sources or logins). */
    data class Counts(val sources: Int, val favourites: Int, val settings: Int, val watch: Int, val series: Int, val reminders: Int, val recordings: Int, val downloads: Int, val hasPin: Boolean)

    fun counts(b: BackupBundle): Counts {
        val st = b.prefs["settings"] ?: emptyMap()
        return Counts(
            sources = arr(b.secure["sources"]).size, favourites = st["favs"]?.ss?.size ?: 0,
            settings = b.prefs.entries.sumOf { (f, m) -> m.keys.count { !skipSetting(f, it) } },
            watch = b.watch.size, series = b.series.size,
            reminders = arr(b.prefs["reminders"]?.get("l")?.s).size, recordings = arr(b.secure["recs"]).size, downloads = arr(b.secure["dls"]).size,
            hasPin = org(st["prefs"]?.s)?.pinHash?.isNotEmpty() == true,
        )
    }

    fun describe(c: Counts): String = buildList {
        add("${c.sources} source(s)"); add("${c.favourites} favourite(s)"); add("${c.settings} setting(s)")
        add("${c.watch} watch position(s)"); if (c.series > 0) add("${c.series} series")
        if (c.reminders > 0) add("${c.reminders} reminder(s)"); if (c.recordings > 0) add("${c.recordings} recording(s)")
        if (c.downloads > 0) add("${c.downloads} download(s)"); if (c.hasPin) add("parental PIN")
    }.joinToString(", ")
}
