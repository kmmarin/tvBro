// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.core.content.edit
import java.io.File

/**
 * The encrypted backup file on Android ([FullBackup] holds the format, the crypto and the merge rules): [collect]
 * gathers the saved settings, the encrypted values (sources with their logins, network share, recordings, downloads)
 * and the watch state; [apply] merges a backup into this device's data. Logins are read and written only through
 * [Store] (EncryptedSharedPreferences under this app's own Keystore key), so they are sealed again with this device's
 * key on import.
 */
object BackupData {
    /** Where Export writes and Import looks first: the app's own folder on shared storage (no permission needed). */
    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "backup").apply { mkdirs() }

    /** Encrypted backups in [dir], newest first. */
    fun files(ctx: Context): List<File> = (dir(ctx).listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(FullBackup.EXT) }.sortedByDescending { it.lastModified() }

    fun collect(ctx: Context, store: Store, appVersion: String): BackupBundle {
        val prefs = FullBackup.PREF_FILES.associateWith { f ->
            ctx.getSharedPreferences(f, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> if (FullBackup.skipSetting(f, k)) null else PrefValue.of(v)?.let { k to it } }.toMap()
        }.filterValues { it.isNotEmpty() }
        val secure = store.secureRaw(FullBackup.SECURE_KEYS) ?: throw BackupException("the stored logins cannot be read on this device")
        val db = WatchDb.get(ctx).readableDatabase
        val watch = ArrayList<WatchRowB>()
        db.rawQuery("SELECT key,series,pos,dur,watched,updated FROM watch", null).use {
            while (it.moveToNext()) watch += WatchRowB(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), it.getInt(4) != 0, it.getLong(5))
        }
        val series = ArrayList<SeriesRowB>()
        db.rawQuery("SELECT key,updated,last_ep,next_ep,next_season,next_num,next_title,finished,active,subs,sub_lang,audio_lang FROM series", null).use {
            while (it.moveToNext()) series += SeriesRowB(
                it.getString(0), it.getLong(1), it.getString(2), it.getString(3), it.getInt(4), it.getInt(5), it.getString(6),
                it.getInt(7) != 0, it.getInt(8) != 0, if (it.isNull(9)) null else it.getInt(9), it.getString(10), it.getString(11),
            )
        }
        return BackupBundle(app = appVersion, pkg = ctx.packageName, created = System.currentTimeMillis(), prefs = prefs, secure = secure, watch = watch, series = series)
    }

    /**
     * Merges [b] into this device's data (see [FullBackup.mergePrefs], [FullBackup.mergeSecure]; watch state: the
     * newer row wins). The encrypted values go first, so a Keystore that does not work changes nothing. The app must
     * restart afterwards: the lists in memory are read once per process.
     */
    fun apply(ctx: Context, store: Store, b: BackupBundle) {
        val pkg = ctx.packageName
        val cur = store.secureRaw(FullBackup.SECURE_KEYS) ?: throw BackupException("the encrypted storage of this device cannot be read")
        if (!store.putSecureRaw(FullBackup.mergeSecure(cur, b.secure, pkg))) throw BackupException("the logins could not be stored on this device")
        for (f in FullBackup.PREF_FILES) {
            val inc = b.prefs[f] ?: continue
            val sp = ctx.getSharedPreferences(f, Context.MODE_PRIVATE)
            val now = sp.all.mapNotNull { (k, v) -> PrefValue.of(v)?.let { k to it } }.toMap()
            val w = FullBackup.mergePrefs(f, now, inc, pkg)
            if (w.isNotEmpty()) sp.edit(commit = true) {
                for ((k, v) in w) when (v.t) {
                    "s" -> putString(k, v.s); "i" -> putInt(k, (v.n ?: 0L).toInt()); "l" -> putLong(k, v.n ?: 0L)
                    "b" -> putBoolean(k, v.b ?: false); "f" -> putFloat(k, v.f ?: 0f); "ss" -> putStringSet(k, HashSet(v.ss ?: emptyList()))
                }
            }
        }
        store.hasSourcesHint = store.sources().isNotEmpty()
        mergeState(WatchDb.get(ctx).writableDatabase, b)
        // alarms for what is still ahead (they are normally set again only at boot)
        val now = System.currentTimeMillis()
        for (r in store.recordings()) if (r.status == "scheduled" && (r.endMs == 0L || r.endMs > now)) RecRepo.schedule(ctx, r)
        Reminders.rescheduleAll(ctx)
        SeriesRules.init(ctx)
    }

    private fun updatedOf(db: SQLiteDatabase, table: String, key: String): Long? =
        db.rawQuery("SELECT updated FROM $table WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun mergeState(db: SQLiteDatabase, b: BackupBundle) {
        db.beginTransaction()
        try {
            for (r in b.watch) {
                val u = updatedOf(db, "watch", r.key)
                if (u != null && u >= r.updated) continue
                db.insertWithOnConflict("watch", null, ContentValues().apply {
                    put("key", r.key); put("series", r.series); put("pos", r.pos); put("dur", r.dur); put("watched", if (r.watched) 1 else 0); put("updated", r.updated)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (r in b.series) {
                val u = updatedOf(db, "series", r.key)
                if (u != null && u >= r.updated) continue
                db.insertWithOnConflict("series", null, ContentValues().apply {
                    put("key", r.key); put("updated", r.updated); put("last_ep", r.lastEp); put("next_ep", r.nextEp)
                    put("next_season", r.nextSeason); put("next_num", r.nextNum); put("next_title", r.nextTitle)
                    put("finished", if (r.finished) 1 else 0); put("active", if (r.active) 1 else 0)
                    if (r.subs == null) putNull("subs") else put("subs", r.subs); put("sub_lang", r.subLang); put("audio_lang", r.audioLang)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
