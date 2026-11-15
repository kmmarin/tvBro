// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro

import org.junit.Assert.*
import org.junit.Test
import io.github.kmmarin.tvbro.data.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Windowed guide loading: batched queries per chunk, demand replaced (not queued), chunk merge and shift. */
class DbEpgTest {
    private val t0 = DbEpg.CHUNK * 600_000L // a chunk boundary

    private class FakeDb(val rows: List<Programme>, val gate: CountDownLatch? = null) : GuideDb {
        val queries: MutableList<List<String>> = Collections.synchronizedList(ArrayList())
        override fun loadMany(src: String, keys: List<String>, from: Long, to: Long): List<Programme> {
            queries += keys
            gate?.await(5, TimeUnit.SECONDS)
            return rows.filter { it.key.startsWith("$src|") && it.key in keys && it.stop > from && it.start < to && it.start >= from - GuideDb.MAX_PROGRAMME }
                .sortedWith(compareBy({ it.key }, { it.start }))
        }
        override fun meta(src: String): EpgDb.Meta? = null
        override fun beginRefresh(src: String) = 0
        override fun insertBatch(src: String, gen: Int, rows: List<Programme>) {}
        override fun commit(src: String, gen: Int, minStart: Long, maxStop: Long) {}
        override fun discard(src: String, gen: Int) {}
        override fun deleteSource(src: String) {}
        override fun checkpoint(force: Boolean) {}
        override fun load(src: String, key: String, from: Long, to: Long): Array<Programme> = error("per-channel load used")
        override fun search(q: String, now: Long, limit: Int) = emptyList<Pair<String, Programme>>()
        override fun forEachCurrent(sink: (ch: String, start: Long, stop: Long, title: String, sub: String?, ep: String?) -> Unit) {}
        override fun desc(ch: String, start: Long): String? = null
        override fun count(src: String) = 0
        override fun sizeBytes() = 0L
    }

    private fun ch(i: Int, withId: Boolean) = Channel("s|$i", "s", "Ch$i", null, "G", "http://example.invalid/$i", if (withId) "id$i" else null, i, null, 0)

    private fun waitFor(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!what()) { assertTrue("timed out", System.currentTimeMillis() < end); Thread.sleep(5) }
    }

    @Test fun visibleRowsLoadInOneQueryPerChunk() {
        // even channels have tvg-id data, odd ones only data under their name key (the fallback)
        val chs = (0 until 30).map { ch(it, withId = it % 2 == 0) }
        val rows = chs.flatMap { c ->
            val k = c.idKey ?: c.nameKey
            (0 until 6).map { h -> Programme(k, t0 + h * 3600L, t0 + (h + 1) * 3600L, "${c.name} $h", null) }
        }
        val db = FakeDb(rows)
        val epg = DbEpg(db, 0)
        epg.want(chs, t0, t0 + 3 * 3600) // one chunk
        waitFor { chs.all { epg.has(it, t0, t0 + 3 * 3600) } }
        assertEquals("one query for all 30 rows", 1, db.queries.size)
        // (odd channels have no id key, so they go by name in the first query already)
        for (c in chs) assertEquals(3, epg.peek(c, t0, t0 + 3 * 3600).size)
        // two chunks: merged without duplicates, in order
        epg.want(chs.take(3), t0 + 3600, t0 + 5 * 3600)
        waitFor { chs.take(3).all { epg.has(it, t0 + 3600, t0 + 5 * 3600) } }
        val merged = epg.peek(chs[0], t0, t0 + 6 * 3600)
        assertEquals((0 until 6).map { t0 + it * 3600L }, merged.map { it.start })
        assertEquals(2, db.queries.size)
    }

    @Test fun idWithoutDataFallsBackToName() {
        val c = ch(1, withId = true)
        val db = FakeDb(listOf(Programme(c.nameKey, t0, t0 + 3600, "by name", null)))
        val epg = DbEpg(db, 0)
        epg.want(listOf(c), t0, t0 + 3600)
        waitFor { epg.has(c, t0, t0 + 3600) }
        assertEquals("by name", epg.peek(c, t0, t0 + 3600).single().title)
        assertEquals(listOf(listOf(c.idKey), listOf(c.nameKey)), db.queries.toList())
    }

    @Test fun newDemandReplacesTheQueue() {
        val gate = CountDownLatch(1)
        val a = (0 until 100).map { ch(it, true) }
        val b = (100 until 110).map { ch(it, true) }
        val db = FakeDb(emptyList(), gate)
        val epg = DbEpg(db, 0)
        epg.want(a, t0, t0 + 3600) // first batch of a starts and blocks
        waitFor { db.queries.size == 1 }
        epg.want(b, t0, t0 + 3600) // the group switched: the rest of a must never load
        gate.countDown()
        waitFor { b.all { epg.has(it, t0, t0 + 3600) } }
        Thread.sleep(50)
        val loaded = db.queries.flatten().toSet()
        assertEquals(DbEpg.BATCH, db.queries[0].size)
        assertTrue(a.drop(DbEpg.BATCH).none { it.idKey in loaded || it.nameKey in loaded })
        // (no data at all: each batch is one query by tvg-id and one by name)
        assertEquals(4, db.queries.size)
    }

    @Test fun holdDropsDemandAndShiftApplies() {
        val c = ch(1, true)
        val db = FakeDb(listOf(Programme(c.idKey!!, t0, t0 + 3600, "x", null)))
        val epg = DbEpg(db, 1800)
        epg.want(listOf(c), t0, t0 + 3600)
        epg.hold()
        waitFor { epg.has(c, t0, t0 + 3600) || db.queries.isEmpty() }
        epg.want(listOf(c), t0, t0 + 3600)
        waitFor { epg.has(c, t0, t0 + 3600) }
        // shown 30 min later (time-shift setting); the stored programme overlaps the shifted chunk
        assertEquals(t0 + 1800, epg.peek(c, t0, t0 + 3 * 3600).single().start)
    }
}
