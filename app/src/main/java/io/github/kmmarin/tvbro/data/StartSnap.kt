// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * What the TV screen showed when the app last went to the background: the group column, the selected group's visible
 * rows (with the focused and the playing channel) and their guide cells for the visible window. Read first thing at
 * start (a few KB, before sources, the channel cache, the guide database or the Rust core) so the first frame shows
 * the screen as it was; the full data replaces it quietly when loaded.
 *
 * Compact binary in the app's files dir, written atomically (temp file + rename) off the main thread. Sealed like the
 * cache files ([Sealed], AES-256-GCM under [CacheKey]): the rows hold stream addresses with the provider login.
 */
class StartSnap(
    val savedAt: Long,
    val screen: String,
    val group: String,
    val groups: List<String>,
    /** The selected group's rows from [first] on (a slice of the full list). */
    val rows: List<Channel>,
    val first: Int,
    val scrollIdx: Int,
    val scrollOff: Int,
    val focusKey: String,
    /** The playing channel (not always among [rows]). */
    val current: Channel?,
    /** Channel key -> its programmes in the visible window (display time). */
    val progs: Map<String, Array<Programme>>,
) {
    companion object {
        const val FILE = "start-snapshot.bin"
        private const val MAGIC = 0x54565331 // "TVS1"
        private const val VERSION = 2 // 2: sealed (1 was plaintext: read as missing and deleted)
        /** Descriptions are cut: the header shows four lines at most. */
        private const val DESC_MAX = 400

        fun file(dir: File) = File(dir, FILE)

        fun write(dir: File, s: StartSnap, key: ByteArray) {
            val f = file(dir)
            val tmp = File(dir, "$FILE.tmp")
            DataOutputStream(SealedOutputStream(tmp.outputStream(), key, 64 * 1024).buffered(16 * 1024)).use { o ->
                o.writeInt(MAGIC); o.writeInt(VERSION)
                o.writeLong(s.savedAt); o.writeUTF(s.screen); o.writeUTF(s.group)
                o.writeInt(s.groups.size); for (g in s.groups) o.writeUTF(g)
                o.writeInt(s.first); o.writeInt(s.scrollIdx); o.writeInt(s.scrollOff); o.writeUTF(s.focusKey)
                o.writeInt(s.rows.size); for (c in s.rows) ch(o, c)
                o.writeBoolean(s.current != null); s.current?.let { ch(o, it) }
                o.writeInt(s.progs.size)
                for ((k, a) in s.progs) {
                    o.writeUTF(k); o.writeInt(a.size)
                    for (p in a) {
                        o.writeUTF(p.key); o.writeLong(p.start); o.writeLong(p.stop); o.writeUTF(p.title.take(300))
                        opt(o, p.desc?.take(DESC_MAX)); opt(o, p.sub?.take(300)); opt(o, p.ep?.take(100))
                    }
                }
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }

        /** The snapshot, or null when there is none or it cannot be read (never throws; an unreadable file is deleted). */
        fun read(dir: File, key: ByteArray): StartSnap? = try {
            val f = file(dir)
            if (!f.exists() || f.length() > 2_000_000) null
            else if (!Sealed.isSealedWith(f, key)) { f.delete(); null }
            else DataInputStream(SealedInputStream(f.inputStream().buffered(16 * 1024), key).buffered(16 * 1024)).use { i ->
                if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
                val at = i.readLong(); val scr = i.readUTF(); val grp = i.readUTF()
                val gs = List(i.readInt()) { i.readUTF() }
                val first = i.readInt(); val si = i.readInt(); val so = i.readInt(); val fk = i.readUTF()
                val rows = List(i.readInt()) { ch(i) }
                val cur = if (i.readBoolean()) ch(i) else null
                val n = i.readInt()
                val progs = HashMap<String, Array<Programme>>(n * 2)
                repeat(n) {
                    val k = i.readUTF()
                    progs[k] = Array(i.readInt()) { Programme(i.readUTF(), i.readLong(), i.readLong(), i.readUTF(), opt(i), opt(i), opt(i)) }
                }
                StartSnap(at, scr, grp, gs, rows, first, si, so, fk, cur, progs)
            }
        } catch (e: Exception) { file(dir).delete(); null }

        private fun opt(o: DataOutputStream, s: String?) { o.writeBoolean(s != null); if (s != null) o.writeUTF(s) }
        private fun opt(i: DataInputStream): String? = if (i.readBoolean()) i.readUTF() else null

        private fun ch(o: DataOutputStream, c: Channel) {
            o.writeUTF(c.key); o.writeUTF(c.sourceId); o.writeUTF(c.name); opt(o, c.logo); o.writeUTF(c.group); o.writeUTF(c.url)
            opt(o, c.tvgId); o.writeInt(c.number); opt(o, c.catchup?.type); opt(o, c.catchup?.template); o.writeInt(c.catchupDays)
            opt(o, c.idKey); o.writeUTF(c.nameKey)
        }

        private fun ch(i: DataInputStream): Channel {
            val key = i.readUTF(); val src = i.readUTF(); val name = i.readUTF(); val logo = opt(i); val group = i.readUTF(); val url = i.readUTF()
            val tvg = opt(i); val num = i.readInt(); val ct = opt(i); val ctt = opt(i); val cd = i.readInt()
            val idKey = opt(i); val nameKey = i.readUTF()
            return Channel(key, src, name, logo, group, url, tvg, num, ct?.let { Catchup(it, ctt) }, cd, idKey, nameKey)
        }
    }
}
