// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.github.kmmarin.tvbro.data.SeriesRule
import java.text.SimpleDateFormat
import java.util.Date

/**
 * What a backup holds: settings (favourites = My list, reminders, search history, guide / playback / recording
 * settings, the channel layout, parental locks and the manual guide mapping), resume points / watched marks / shows'
 * progress ([watch]) and the series-recording rules.
 * Never in it: playlist logins (they are not in [Settings] at all), the PIN (its hash is blanked), the share
 * password and user name, recordings, caches.
 */
@Serializable
data class DesktopBackup(
    val format: String = BackupFile.FORMAT,
    val version: Int = BackupFile.VERSION,
    val createdAt: Long = 0,
    val note: String = "",
    val settings: Settings,
    val series: List<SeriesRule> = emptyList(),
    val watch: WatchData? = null,
)

class BadBackup(msg: String) : Exception(msg)

/** Backup files: written, checked as a whole before anything is restored, summarised for the confirmation. */
object BackupFile {
    const val FORMAT = "tvbro-desktop-backup"
    /** 1: settings + series rules (first desktop versions, no format field); 2: + watch state. */
    const val VERSION = 2
    const val MAX_BYTES = 32L * 1024 * 1024

    fun export(s: Settings, rules: List<SeriesRule>, watch: WatchData, now: Long = System.currentTimeMillis()): String =
        Json2.json.encodeToString(DesktopBackup.serializer(), DesktopBackup(
            createdAt = now,
            note = "tvBro desktop backup: favourites, resume points, reminders, settings, channel layout, locks, guide mapping, series recordings. No logins or PINs.",
            settings = s.copy(lastUi = "", resume = emptyMap(), prefs = s.prefs.copy(pinHash = ""), smb = s.smb.copy(user = "", domain = "")),
            series = rules, watch = watch,
        ))

    private fun need(ok: Boolean, msg: String) { if (!ok) throw BadBackup(msg) }
    private fun strs(c: Collection<String>, what: String, limit: Int) {
        need(c.size <= limit, "$what: more than $limit entries")
        need(c.all { it.length <= 1000 }, "$what: an entry is too long")
    }

    /** Reads and checks a whole backup. Throws [BadBackup] (with a reason to show) when anything is wrong. */
    fun check(text: String): DesktopBackup {
        need(text.length <= MAX_BYTES, "the file is too large for a tvBro backup")
        val o = try { Json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null } ?: throw BadBackup("not a tvBro backup (not a JSON object)")
        val fmt = (o["format"] as? JsonPrimitive)?.contentOrNull
        need(fmt == null || fmt == FORMAT, if (fmt == "tvbro-web-backup") "a backup of the web app: restore it in tvBro web" else "not a tvBro desktop backup (format)")
        val ver = (o["version"] as? JsonPrimitive)?.intOrNull
        need(ver != null && ver >= 1, "not a tvBro backup (version)")
        need(ver!! <= VERSION, "made by a newer tvBro: update this one first")
        need(o["settings"] is JsonObject, "not a tvBro backup (no settings)")
        val b = try { Json2.json.decodeFromJsonElement(DesktopBackup.serializer(), o) } catch (e: Exception) {
            throw BadBackup("damaged backup (${e.message?.lineSequence()?.firstOrNull()?.take(120) ?: e.javaClass.simpleName})")
        }
        val s = b.settings; val p = s.prefs
        strs(s.myList, "My list", 50_000)
        need(s.reminders.size <= 5000, "reminders: more than 5000")
        strs(s.searchHistory, "search history", 1000)
        need(s.epgMap.size <= 50_000 && s.epgMap.all { (k, v) -> k.isNotEmpty() && v.isNotEmpty() && k.length <= 1000 && v.length <= 1000 }, "guide mapping: channel -> guide channel")
        strs(p.hiddenCh, "hidden channels", 100_000); strs(p.order, "channel order", 200_000); strs(p.hiddenGroups, "hidden groups", 20_000)
        strs(p.groupOrder, "group order", 20_000); strs(p.locked, "locks", 20_000); strs(p.lockedCh, "locked channels", 20_000)
        need(p.names.size <= 100_000 && p.groupNames.size <= 20_000, "renames: too many")
        need(p.custom.size <= 500 && p.custom.values.all { it.size <= 20_000 }, "own groups: too many")
        need(b.series.size <= 500 && b.series.all { it.chKey.isNotEmpty() && it.title.isNotEmpty() }, "series recordings: channel and title")
        b.watch?.let { w ->
            need(w.items.size <= 200_000 && w.series.size <= 50_000 && w.snaps.size <= 50_000, "watch state: too many entries")
            need(w.items.values.all { it.posMs >= 0 && it.durMs >= 0 }, "watch state: negative positions")
        }
        need(s.epgHours in 1..24 * 14 && s.catalogueHours in 1..24 * 60 && s.epgShiftMin in -720..720, "settings: values out of range")
        return b
    }

    /** Five short lines for the confirmation: what the backup holds and what stays. */
    fun summary(b: DesktopBackup): List<String> {
        val s = b.settings; val p = s.prefs
        val made = if (b.createdAt > 0) " (made ${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(b.createdAt))})" else ""
        val w = b.watch
        return listOf(
            "Restore this backup?$made",
            "My list ${s.myList.size} · reminders ${s.reminders.size} · series recordings ${b.series.size} · searches ${s.searchHistory.size}",
            if (w == null) "No resume points in it (older backup): yours stay" else "Resume points and watched marks ${w.items.size} · shows' progress ${w.series.size}",
            "Channel layout: ${p.names.size + p.groupNames.size} renamed, ${p.hiddenCh.size + p.hiddenGroups.size} hidden, ${p.custom.size} own groups · locks ${p.locked.size + p.lockedCh.size} · guide mappings ${s.epgMap.size}",
            "Replaces these and the settings. Kept: playlists and logins, the PIN, recordings.",
        )
    }

    /** The settings after a restore: the backup's, keeping this device's PIN, screen place and share login. */
    fun restored(cur: Settings, b: DesktopBackup): Settings {
        val s = b.settings
        val smb = if (s.smb.user.isBlank()) s.smb.copy(user = cur.smb.user, domain = cur.smb.domain) else s.smb
        return s.copy(lastUi = cur.lastUi, resume = emptyMap(), prefs = s.prefs.copy(pinHash = cur.prefs.pinHash), smb = smb)
    }

    /** Series rules after a restore: the backup's are added, existing ones kept (no scheduled recording is cancelled). */
    fun mergeRules(cur: List<SeriesRule>, add: List<SeriesRule>): List<SeriesRule> =
        cur + add.filter { a -> cur.none { it.id == a.id || (it.chKey == a.chKey && it.title.equals(a.title, true)) } }.distinctBy { it.chKey + "|" + it.title.lowercase() }
}
