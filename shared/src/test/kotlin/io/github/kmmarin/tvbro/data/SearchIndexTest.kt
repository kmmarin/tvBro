// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** SqlDb over JDBC SQLite (same FTS4 engine as Android's framework SQLite). */
class JdbcDb(private val c: Connection) : SqlDb {
    init { c.autoCommit = true }
    private fun bind(st: java.sql.PreparedStatement, a: Array<out Any?>) = a.forEachIndexed { i, v -> st.setObject(i + 1, v) }
    override fun exec(sql: String, args: Array<Any?>) { c.prepareStatement(sql).use { bind(it, args); it.execute() } }
    override fun query(sql: String, args: Array<String>, row: (SqlRow) -> Unit) {
        c.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> val r = object : SqlRow { override fun str(i: Int) = rs.getString(i + 1); override fun long(i: Int) = rs.getLong(i + 1) }; while (rs.next()) row(r) }
        }
    }
    override fun prepare(sql: String): SqlStmt { val st = c.prepareStatement(sql); return object : SqlStmt { override fun insert(vararg a: Any?): Long { bind(st, a); st.executeUpdate(); return 0 } } }
    override fun begin() { c.autoCommit = false }
    override fun commit() { c.commit(); c.autoCommit = true }
}

class SearchIndexTest {
    private val now = 1_800_000_000L

    private fun build(block: SearchIndex.Writer.() -> Unit): SqlDb {
        val db = JdbcDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
        SearchIndex.create(db)
        SearchIndex.Writer(db).apply { block(); finish(now) }
        return db
    }

    private fun movie(id: String, name: String, cat: String = "Films") = VodItem("m|s|$id", "s", "movie", id, name, null, cat, "7.1", "mkv")
    private fun show(id: String, name: String) = VodItem("s|s|$id", "s", "series", id, name, null, "Shows", null, "")

    @Test fun foldRemovesDiacriticsAndPunctuation() {
        assertEquals("pokemon la serie", Fold.text("Pokémon: La Série!"))
        assertEquals("strasse muller", Fold.text("Straße  Müller"))
        assertEquals("barc* fc*", Fold.match("Barc, FC"))
        assertNull(Fold.match("  -- "))
    }

    @Test fun prefixPerWord() {
        val db = build {
            channel("s|1", "FC Barcelona TV", "s"); channel("s|2", "BBC One", "s")
            vod(movie("1", "Barcelona (2019)")); vod(movie("2", "Vicky Cristina Barcelona")); vod(movie("3", "Madrid Nights"))
            programme("Barcelona vs Real Madrid", "s|sport", now - 600, now + 3000, null, null)
        }
        val r = SearchIndex.search(db, "barc", now - 86400)
        assertEquals(listOf("FC Barcelona TV"), r.channels.map { it.name })
        assertEquals(2, r.movies.size)
        assertEquals(1, r.programmes.size)
        // every word must match a word prefix
        val r2 = SearchIndex.search(db, "barc mad", now - 86400)
        assertTrue(r2.movies.isEmpty())
        assertEquals("Barcelona vs Real Madrid", r2.programmes.single().title)
        assertTrue(SearchIndex.search(db, "arcelona", now - 86400).isEmpty) // no infix match
    }

    @Test fun diacriticsFoldBothWays() {
        val db = build { vod(show("1", "Pokémon")); vod(movie("1", "Amélie")); channel("s|1", "Müller TV", "s") }
        assertEquals("Pokémon", SearchIndex.search(db, "pokemon", 0).series.single().name)
        assertEquals("Amélie", SearchIndex.search(db, "AMELIE", 0).movies.single().name)
        assertEquals("Müller TV", SearchIndex.search(db, "müll", 0).channels.single().name)
        assertEquals("Müller TV", SearchIndex.search(db, "mull", 0).channels.single().name)
    }

    @Test fun rankingExactThenPrefixThenWord() {
        val db = build {
            channel("s|1", "Sky Newsroom Extra", "s"); channel("s|2", "BBC News", "s"); channel("s|3", "News", "s"); channel("s|4", "Newsroom", "s")
        }
        assertEquals(listOf("News", "Newsroom", "BBC News", "Sky Newsroom Extra"), SearchIndex.search(db, "news", 0).channels.map { it.name })
        assertEquals(0, Fold.rank("News", "news")); assertEquals(1, Fold.rank("Newsroom", "news"))
        assertEquals(2, Fold.rank("BBC World News", "bbc news")); assertEquals(3, Fold.rank("News BBC", "bbc news"))
    }

    @Test fun limitPerSection() {
        val db = build { for (i in 1..80) vod(movie("$i", "Star Film $i")); channel("s|1", "Star Channel", "s") }
        val r = SearchIndex.search(db, "star", 0, perSection = 20)
        assertEquals(20, r.movies.size); assertEquals(1, r.channels.size)
    }

    @Test fun programmesPastNowFuture() {
        val db = build {
            programme("Evening News", "s|a", now - 5 * 86400, now - 5 * 86400 + 1800, null, null) // older than the catch-up window
            programme("Evening News", "s|a", now - 7200, now - 5400, null, null)                // yesterday-ish: catch-up
            programme("Evening News", "s|b", now - 600, now + 1200, "Late edition", "S1 E2")     // on now
            programme("Evening News", "s|c", now + 3600, now + 5400, null, null)                 // future
        }
        val hits = SearchIndex.search(db, "even", now - 2 * 86400).programmes
        assertEquals(3, hits.size)
        assertEquals("Late edition", hits.first { it.ch == "s|b" }.sub)
        assertEquals(1, SearchIndex.search(db, "late edit", now - 2 * 86400).programmes.size) // episode titles are indexed
        val chans = mapOf(
            "s|a" to Channel("a", "s", "A", null, "G", "u", "a", 1, Catchup("shift", null), 3),
            "s|b" to Channel("b", "s", "B", null, "G", "u", "b", 2, null, 0),
            "s|c" to Channel("c", "s", "C", null, "G", "u", "c", 3, null, 0),
        )
        val groups = ProgGroups.group(hits, { chans[it] }, { c, h -> ProgTime.classify(c, h.start, h.stop, now) }, { it.key })
        assertEquals(listOf("b", "c", "a"), groups.map { it.first.key }) // now, then future, then catch-up
        assertEquals(ProgState.PAST_CATCHUP, ProgTime.classify(chans["s|a"], now - 7200, now - 5400, now))
    }

    @Test fun cataloguePages() {
        val db = build { for (i in 1..25) vod(movie("$i", "Film $i", if (i % 2 == 0) "Drama" else "Action")); vod(show("1", "Show")) }
        assertEquals(listOf("Action", "Drama"), SearchIndex.categories(db, SearchIndex.MOVIE))
        assertEquals(10, SearchIndex.page(db, SearchIndex.MOVIE, null, 0, 10).size)
        assertEquals(5, SearchIndex.page(db, SearchIndex.MOVIE, null, 20, 10).size)
        assertEquals(12, SearchIndex.page(db, SearchIndex.MOVIE, "Drama", 0, 100).size)
        assertEquals("mkv", SearchIndex.page(db, SearchIndex.MOVIE, null, 0, 1).single().ext)
        assertEquals(1, SearchIndex.count(db, SearchIndex.SERIES))
    }

    @Test fun streamAddressesAreNotStored() {
        val withLogin = VodItem("v|s|1", "s", "movie", "1", "Secret Film", null, "Films", null, "", "http://tv.example/movie/user/pass/1.mkv")
        val db = build { vod(withLogin) }
        assertNull(SearchIndex.search(db, "secret", 0).movies.single().url)
        var stored: String? = "?"
        db.query("SELECT url FROM item", emptyArray()) { stored = it.str(0) }
        assertNull(stored)
        // a platform that still needs them can opt in
        val db2 = JdbcDb(DriverManager.getConnection("jdbc:sqlite::memory:")).also { SearchIndex.create(it); SearchIndex.Writer(it, storeUrls = true).apply { vod(withLogin); finish(now) } }
        assertEquals(withLogin.url, SearchIndex.search(db2, "secret", 0).movies.single().url)
    }
}
