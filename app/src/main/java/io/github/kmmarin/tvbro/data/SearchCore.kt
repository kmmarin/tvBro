// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import io.github.kmmarin.tvbro.core.CoreSearchBuilder
import io.github.kmmarin.tvbro.core.CoreSearchIndex
import io.github.kmmarin.tvbro.core.FfiIndexChannel
import io.github.kmmarin.tvbro.core.FfiIndexProgramme
import io.github.kmmarin.tvbro.core.FfiIndexVod
import io.github.kmmarin.tvbro.core.FfiSearchHit

/**
 * Queries on a built search index (search.db): Kotlin's [SearchIndex] over a [SqlDb], or the Rust core's
 * (row SEARCH of [CoreSwitch]). Like epg.db, one process opens search.db with one SQLite build only.
 */
interface SearchReader : AutoCloseable {
    fun search(q: String, pastFrom: Long, perSection: Int = 30, maxProg: Int = 300): SearchResult
    fun page(kind: Int, cat: String?, offset: Int, limit: Int): List<VodItem>
    fun categories(kind: Int): List<String>
    fun count(kind: Int): Int
    fun meta(k: String): String?
}

class SqlSearch(private val db: SqlDb, private val onClose: () -> Unit = {}) : SearchReader {
    override fun search(q: String, pastFrom: Long, perSection: Int, maxProg: Int) = SearchIndex.search(db, q, pastFrom, perSection, maxProg)
    override fun page(kind: Int, cat: String?, offset: Int, limit: Int) = SearchIndex.page(db, kind, cat, offset, limit)
    override fun categories(kind: Int) = SearchIndex.categories(db, kind)
    override fun count(kind: Int) = SearchIndex.count(db, kind)
    override fun meta(k: String) = SearchIndex.meta(db, k)
    override fun close() = onClose()
}

/** The core's reader (read-only connection of the core's SQLite). */
class CoreSearch(path: String) : SearchReader {
    private val ix = CoreSearchIndex.open(path)

    override fun search(q: String, pastFrom: Long, perSection: Int, maxProg: Int): SearchResult {
        val r = ix.search(q, pastFrom, perSection.toUInt(), maxProg.toUInt())
        return SearchResult(r.channels.map(::hit), r.movies.map(::hit), r.series.map(::hit), r.programmes.map { ProgHit(it.title, it.ch, it.start, it.stop, it.sub, it.ep) })
    }
    override fun page(kind: Int, cat: String?, offset: Int, limit: Int) = ix.page(kind.toLong(), cat, offset.toUInt(), limit.toUInt()).map { hit(it).toVod() }
    override fun categories(kind: Int) = ix.categories(kind.toLong())
    override fun count(kind: Int) = ix.count(kind.toLong()).toInt()
    override fun meta(k: String) = ix.meta(k)
    override fun close() = ix.destroy()

    private fun hit(h: FfiSearchHit) = SearchHit(h.kind.toInt(), h.key, h.name, h.img, h.src, h.xid, h.cat, h.rating, h.ext, h.url)
}

/** Rows of one index rebuild, in the order the rebuild produces them (item ids follow that order). */
interface IndexSink {
    fun channel(key: String, name: String, src: String)
    fun vod(v: VodItem)
    fun programme(title: String, ch: String, start: Long, stop: Long, sub: String?, ep: String?)
    /** Reads the current programmes of [db] itself; false when they must come through [programme]. */
    fun guide(db: GuideDb): Boolean = false
    /** Finishes the file; the `counts` meta value (channels,movies,series,titles,programmes). */
    fun finish(builtAt: Long): String
}

class KotlinIndexSink(db: SqlDb) : IndexSink {
    private val w = SearchIndex.Writer(db)
    override fun channel(key: String, name: String, src: String) = w.channel(key, name, src)
    override fun vod(v: VodItem) = w.vod(v)
    override fun programme(title: String, ch: String, start: Long, stop: Long, sub: String?, ep: String?) = w.programme(title, ch, start, stop, sub, ep)
    override fun finish(builtAt: Long): String { w.finish(builtAt); return w.counts.joinToString(",") + ",${w.programmes}" }
}

/**
 * The core's index writer. Rows cross in batches of 2000, one kind at a time so the item ids keep the call order;
 * stream addresses are never passed. [tolerant] (shadow mode): a core failure stops feeding the core and is reported
 * by [finish] returning null, so it never breaks Kotlin's build. [nanos]: time spent in the core.
 */
class CoreIndexSink(path: String, private val tolerant: Boolean = false) : IndexSink {
    private val b = CoreSearchBuilder.create(path)
    private val chs = ArrayList<FfiIndexChannel>()
    private val vods = ArrayList<FfiIndexVod>()
    private val progs = ArrayList<FfiIndexProgramme>()
    var nanos = 0L; private set
    var failure: String? = null; private set

    private fun run(f: () -> Unit) {
        if (failure != null) return
        val t = System.nanoTime()
        try { f() } catch (e: Exception) { if (!tolerant) throw e; failure = e.javaClass.simpleName }
        finally { nanos += System.nanoTime() - t }
    }

    private fun flush() {
        if (chs.isNotEmpty()) { val l = chs.toList(); chs.clear(); run { b.channels(l) } }
        if (vods.isNotEmpty()) { val l = vods.toList(); vods.clear(); run { b.vods(l) } }
        if (progs.isNotEmpty()) { val l = progs.toList(); progs.clear(); run { b.programmes(l) } }
    }

    override fun channel(key: String, name: String, src: String) {
        if (vods.isNotEmpty() || progs.isNotEmpty()) flush()
        chs += FfiIndexChannel(key, name, src); if (chs.size >= BATCH) flush()
    }

    override fun vod(v: VodItem) {
        if (chs.isNotEmpty() || progs.isNotEmpty()) flush()
        vods += FfiIndexVod(v.key, v.sourceId, v.kind, v.id, v.name, v.poster, v.category, v.rating, v.ext); if (vods.size >= BATCH) flush()
    }

    override fun programme(title: String, ch: String, start: Long, stop: Long, sub: String?, ep: String?) {
        if (chs.isNotEmpty() || vods.isNotEmpty()) flush()
        progs += FfiIndexProgramme(title, ch, start, stop, sub, ep); if (progs.size >= BATCH) flush()
    }

    /** With the guide store on the core too, the programmes never cross to Kotlin. */
    override fun guide(db: GuideDb): Boolean {
        if (db !is CoreGuideDb) return false
        flush(); run { b.programmesFromGuide(db.store) }
        return true
    }

    override fun finish(builtAt: Long): String {
        flush()
        var out = ""
        run { val c = b.finish(builtAt); out = "${c.channels},${c.movies},${c.series},${c.titles},${c.programmes}" }
        b.destroy()
        return out
    }

    fun abort() { try { b.abort() } finally { b.destroy() } }

    private companion object { const val BATCH = 2000 }
}
