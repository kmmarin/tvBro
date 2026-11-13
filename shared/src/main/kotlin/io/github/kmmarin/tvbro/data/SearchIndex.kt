// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import java.text.Normalizer

/** Minimal SQL access so the index logic runs on Android's SQLite and on JDBC SQLite (tests, desktop). */
interface SqlDb {
    fun exec(sql: String, args: Array<Any?> = emptyArray())
    fun query(sql: String, args: Array<String>, row: (SqlRow) -> Unit)
    fun prepare(sql: String): SqlStmt
    fun begin()
    fun commit()
}
interface SqlRow { fun str(i: Int): String?; fun long(i: Int): Long }
interface SqlStmt { fun insert(vararg a: Any?): Long }

/** Text folding shared by the index and queries: lower case, diacritics removed, punctuation to spaces. */
object Fold {
    private val marks = Regex("\\p{M}+")
    fun text(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()
        val b = StringBuilder(n.length)
        var space = true
        for (c in n) {
            val r = when (c) { 'ß' -> "ss"; 'æ' -> "ae"; 'œ' -> "oe"; 'ø' -> "o"; 'đ', 'ð' -> "d"; 'ł' -> "l"; 'þ' -> "th"; 'ı' -> "i"; else -> null }
            if (r != null) { b.append(r); space = false }
            else if (c.isLetterOrDigit()) { b.append(c); space = false }
            else if (!space) { b.append(' '); space = true }
        }
        return b.toString().trim()
    }

    fun tokens(q: String): List<String> = text(q).split(' ').filter { it.isNotEmpty() }.take(8)

    /** FTS4 query: every word must match as a prefix ("barc" finds "Barcelona"). */
    fun match(q: String): String? = tokens(q).takeIf { it.isNotEmpty() }?.joinToString(" ") { "$it*" }

    /** Lower is better: exact title, title starts with the query, every word starts a word in order, anything else. */
    fun rank(name: String, q: String): Int {
        val n = text(name); val f = text(q)
        if (n == f) return 0
        if (n.startsWith(f)) return 1
        val words = n.split(' '); val qt = f.split(' ')
        var wi = 0
        for (t in qt) { while (wi < words.size && !words[wi].startsWith(t)) wi++; if (wi == words.size) return 3; wi++ }
        return 2
    }
}

data class SearchHit(val kind: Int, val key: String, val name: String, val img: String?, val src: String, val xid: String, val cat: String?, val rating: String?, val ext: String?, val url: String?) {
    fun toVod(): VodItem = VodItem(key, src, if (kind == SearchIndex.SERIES) "series" else "movie", xid, name, img, cat ?: "", rating, ext ?: "", url)
}

/** A matching programme. [ch] is the EPG key (source | channel id); times are epoch seconds, unshifted. */
data class ProgHit(val title: String, val ch: String, val start: Long, val stop: Long, val sub: String?, val ep: String?)

class SearchResult(val channels: List<SearchHit>, val movies: List<SearchHit>, val series: List<SearchHit>, val programmes: List<ProgHit>) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() && programmes.isEmpty()
    companion object { val EMPTY = SearchResult(emptyList(), emptyList(), emptyList(), emptyList()) }
}

/**
 * Full-text search index (SQLite FTS4, prefix indexes, contentless) over channel names, movie and series titles and
 * programme titles + episode titles. Built in one pass during sync into a fresh database file that the platform swaps
 * in atomically; queries read only the columns the result view needs.
 */
object SearchIndex {
    const val CHANNEL = 0; const val MOVIE = 1; const val SERIES = 2; const val PROG = 3
    private val FTS = arrayOf("f_ch", "f_mov", "f_ser", "f_prog")

    fun create(db: SqlDb) {
        db.exec("CREATE TABLE item(id INTEGER PRIMARY KEY, kind INTEGER, k TEXT, name TEXT, img TEXT, src TEXT, xid TEXT, cat TEXT, rating TEXT, ext TEXT, url TEXT)")
        for (f in FTS) db.exec("CREATE VIRTUAL TABLE $f USING fts4(content=\"\", t, prefix=\"2,3\")")
        db.exec("CREATE TABLE pr(tid INTEGER, ch TEXT, start INTEGER, stop INTEGER, sub TEXT, ep TEXT)")
        db.exec("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
    }

    /**
     * Streams rows into a freshly created index; call [finish] once at the end. Stream addresses are not stored
     * (a playlist VOD address carries the provider login and the index is not encrypted): hits come back with
     * url = null and the caller resolves the address from the source at play time. [storeUrls] keeps the old
     * behaviour for a platform that cannot resolve yet.
     */
    class Writer(private val db: SqlDb, private val storeUrls: Boolean = false) {
        private val item = db.prepare("INSERT INTO item(id,kind,k,name,img,src,xid,cat,rating,ext,url) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
        private val fts = FTS.map { db.prepare("INSERT INTO $it(docid,t) VALUES(?,?)") }
        private val pr = db.prepare("INSERT INTO pr(tid,ch,start,stop,sub,ep) VALUES(?,?,?,?,?,?)")
        private val titles = HashMap<String, Long>()
        private var next = 1L
        private var pending = 0
        val counts = IntArray(4)
        var programmes = 0; private set

        init { db.begin() }

        private fun tick() { if (++pending >= 2000) { db.commit(); db.begin(); pending = 0 } }

        private fun add(kind: Int, key: String, name: String, text: String, img: String?, src: String, xid: String, cat: String?, rating: String?, ext: String?, url: String?): Long {
            val id = next++
            item.insert(id, kind, key, name, img, src, xid, cat, rating, ext, url)
            fts[kind].insert(id, Fold.text(text))
            counts[kind]++; tick()
            return id
        }

        fun channel(key: String, name: String, src: String) { add(CHANNEL, key, name, name, null, src, "", null, null, null, null) }

        fun vod(v: VodItem) {
            val k = if (v.kind == "series") SERIES else MOVIE
            add(k, v.key, v.name, v.name, v.poster, v.sourceId, v.id, v.category, v.rating, v.ext, if (storeUrls) v.url else null)
        }

        fun programme(title: String, ch: String, start: Long, stop: Long, sub: String?, ep: String?) {
            if (title.isBlank()) return
            val tk = title.lowercase() + "\u0001" + (sub?.lowercase() ?: "") // one document per title + episode title
            val tid = titles[tk] ?: add(PROG, "", title, title + (if (sub != null) " $sub" else ""), null, "", "", null, null, null, null).also { titles[tk] = it }
            pr.insert(tid, ch, start, stop, sub, ep)
            programmes++; tick()
        }

        fun finish(builtAt: Long) {
            db.exec("CREATE INDEX pr_t ON pr(tid, start)")
            db.exec("CREATE INDEX item_k ON item(kind, cat, id)")
            db.exec("INSERT OR REPLACE INTO meta(k,v) VALUES('built',?)", arrayOf(builtAt.toString()))
            db.exec("INSERT OR REPLACE INTO meta(k,v) VALUES('counts',?)", arrayOf(counts.joinToString(",") + ",$programmes"))
            for (f in FTS) db.exec("INSERT INTO $f($f) VALUES('optimize')")
            db.commit()
        }
    }

    private const val COLS = "i.kind,i.k,i.name,i.img,i.src,i.xid,i.cat,i.rating,i.ext,i.url"
    private fun hit(r: SqlRow) = SearchHit(r.long(0).toInt(), r.str(1) ?: "", r.str(2) ?: "", r.str(3), r.str(4) ?: "", r.str(5) ?: "", r.str(6), r.str(7), r.str(8), r.str(9))

    private fun candidates(db: SqlDb, kind: Int, match: String, max: Int): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        db.query("SELECT $COLS FROM ${FTS[kind]} f JOIN item i ON i.id=f.docid WHERE f.t MATCH ? ORDER BY length(i.name) LIMIT $max", arrayOf(match)) { out += hit(it) }
        return out
    }

    private fun ranked(l: List<SearchHit>, q: String, limit: Int) =
        l.map { it to Fold.rank(it.name, q) }.sortedWith(compareBy<Pair<SearchHit, Int>>({ it.second }, { it.first.name.length }, { it.first.name })).take(limit).map { it.first }

    /**
     * Ranked results, at most [perSection] per section. Programmes are limited to those ending after [pastFrom]
     * (the catch-up window) and to [maxProg] rows, newest titles ranked like the rest.
     */
    fun search(db: SqlDb, q: String, pastFrom: Long, perSection: Int = 30, maxProg: Int = 300): SearchResult {
        val match = Fold.match(q) ?: return SearchResult.EMPTY
        if (Fold.text(q).length < 2) return SearchResult.EMPTY
        val cand = 6 * perSection
        val ch = ranked(candidates(db, CHANNEL, match, cand), q, perSection)
        val mov = ranked(candidates(db, MOVIE, match, cand), q, perSection)
        val ser = ranked(candidates(db, SERIES, match, cand), q, perSection)
        val titles = ArrayList<Pair<Long, String>>()
        db.query("SELECT i.id,i.name FROM ${FTS[PROG]} f JOIN item i ON i.id=f.docid WHERE f.t MATCH ? ORDER BY length(i.name) LIMIT ${cand * 2}", arrayOf(match)) { titles += it.long(0) to (it.str(1) ?: "") }
        val best = titles.sortedWith(compareBy({ Fold.rank(it.second, q) }, { it.second.length })).take(60)
        val progs = ArrayList<ProgHit>()
        if (best.isNotEmpty()) {
            val name = best.toMap()
            db.query("SELECT tid,ch,start,stop,sub,ep FROM pr WHERE tid IN (${best.joinToString(",") { it.first.toString() }}) AND stop>? ORDER BY start LIMIT $maxProg", arrayOf(pastFrom.toString())) {
                progs += ProgHit(name[it.long(0)] ?: "", it.str(1) ?: "", it.long(2), it.long(3), it.str(4), it.str(5))
            }
        }
        return SearchResult(ch, mov, ser, progs)
    }

    /** One page of the stored catalogue: [kind] MOVIE or SERIES, [cat] a category name or null for all. */
    fun page(db: SqlDb, kind: Int, cat: String?, offset: Int, limit: Int): List<VodItem> {
        val out = ArrayList<VodItem>()
        val where = if (cat == null) "kind=?" else "kind=? AND cat=?"
        val args = if (cat == null) arrayOf(kind.toString()) else arrayOf(kind.toString(), cat)
        db.query("SELECT $COLS FROM item i WHERE $where ORDER BY id LIMIT $limit OFFSET $offset", args) { out += hit(it).toVod() }
        return out
    }

    /** Category names in catalogue order (first appearance). */
    fun categories(db: SqlDb, kind: Int): List<String> {
        val out = ArrayList<String>()
        db.query("SELECT cat FROM item WHERE kind=? GROUP BY cat ORDER BY MIN(id)", arrayOf(kind.toString())) { it.str(0)?.let { c -> out += c } }
        return out
    }

    fun count(db: SqlDb, kind: Int): Int {
        var n = 0
        db.query("SELECT COUNT(*) FROM item WHERE kind=?", arrayOf(kind.toString())) { n = it.long(0).toInt() }
        return n
    }

    fun meta(db: SqlDb, k: String): String? {
        var v: String? = null
        db.query("SELECT v FROM meta WHERE k=?", arrayOf(k)) { v = it.str(0) }
        return v
    }
}

/** Groups programme hits by channel for the results view: channels with something on now first, then the soonest upcoming, then catch-up. */
object ProgGroups {
    fun <C> group(hits: List<ProgHit>, channelOf: (String) -> C?, state: (C, ProgHit) -> ProgState, keyOf: (C) -> String): List<Pair<C, List<ProgHit>>> {
        val by = LinkedHashMap<String, Pair<C, MutableList<ProgHit>>>()
        for (h in hits) {
            val c = channelOf(h.ch) ?: continue
            if (state(c, h) == ProgState.PAST) continue // finished and not in the archive: nothing to offer
            by.getOrPut(keyOf(c)) { c to ArrayList() }.second += h
        }
        fun score(p: Pair<C, List<ProgHit>>): Long {
            val s = p.second.map { state(p.first, it) }
            return when {
                ProgState.NOW in s -> 0L
                ProgState.FUTURE in s -> 1L shl 40 or p.second.filter { state(p.first, it) == ProgState.FUTURE }.minOf { it.start }
                else -> 2L shl 40 or (Long.MAX_VALUE shr 30) - p.second.maxOf { it.start }
            }
        }
        return by.values.map { it.first to it.second.toList() }.sortedBy { score(it) }
    }
}
