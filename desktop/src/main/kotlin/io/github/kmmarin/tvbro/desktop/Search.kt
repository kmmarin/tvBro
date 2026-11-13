// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.kmmarin.tvbro.data.*
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** SqlDb over JDBC SQLite (sqlite-jdbc bundles the native library for Windows x64 and Linux arm64). */
class JdbcDb(val c: Connection) : SqlDb {
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

/**
 * Full-text search index (shared SearchIndex: FTS4, prefix per word, diacritics folded, ranked) in search.db, rebuilt
 * after each load into a new file and swapped in atomically. Queries and builds run on one background thread.
 */
object SearchStore {
    private val file = File(Paths.cache, "search.db")
    private var db: JdbcDb? = null
    private val lock = Any()

    fun rebuild(channels: List<Channel>, vod: List<VodItem>, guide: Map<String, Array<Programme>>) = synchronized(lock) {
        val t = System.currentTimeMillis()
        val tmp = File(Paths.cache, "search.db.new"); tmp.delete()
        try {
            DriverManager.getConnection("jdbc:sqlite:" + tmp.path).use { c ->
                val d = JdbcDb(c)
                SearchIndex.create(d)
                val w = SearchIndex.Writer(d)
                for (ch in channels) w.channel(ch.key, ch.name, ch.sourceId)
                for (v in vod) w.vod(v)
                for ((k, a) in guide) for (p in a) w.programme(p.title, k, p.start, p.stop, p.sub, p.ep)
                w.finish(t)
            }
            db?.c?.close(); db = null
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            Perms.file(file) // SQLite creates it with the umask
            Diag.log("search index: ${channels.size} channels, ${vod.size} titles, built in ${System.currentTimeMillis() - t} ms")
        } catch (e: Exception) { Diag.log("search index build failed (${e.javaClass.simpleName}: ${e.message})"); tmp.delete() }
    }

    fun search(q: String, pastFrom: Long): SearchResult = synchronized(lock) {
        try {
            val d = db ?: (if (file.exists()) JdbcDb(DriverManager.getConnection("jdbc:sqlite:" + file.path)).also { db = it } else return SearchResult.EMPTY)
            SearchIndex.search(d, q, pastFrom)
        } catch (e: Exception) { Diag.log("search failed (${e.javaClass.simpleName})"); SearchResult.EMPTY }
    }
}

@Composable
fun SearchScreen(app: AppState) {
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf(SearchResult.EMPTY) }
    LaunchedEffect(q, app.channels) {
        if (Fold.text(q).length < 2) { res = SearchResult.EMPTY; return@LaunchedEffect }
        delay(250) // debounce typing
        res = withContext(Dispatchers.IO) { SearchStore.search(q, System.currentTimeMillis() / 1000 - 8 * 86400) }
    }
    val keep = { app.update { it.copy(searchHistory = SearchHistory.add(it.searchHistory, q)) } }
    val sh = app.settings.epgShiftMin * 60L
    val byEpg = remember(app.shown) { val m = HashMap<String, Channel>(); for (c in app.shown) { m[c.nameKey] = c; c.idKey?.let { m[it] = c } }; m }
    val byKey = remember(app.shown) { app.shown.associateBy { it.key } }
    Column {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().onFocusChanged { typing = it.isFocused }
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { keep(); true } else false },
            placeholder = { T("Search channels, programmes, movies, shows (Enter keeps it in the history)", 14, Theme.dim) }, singleLine = true)
        if (Fold.text(q).length < 2) {
            val h = app.settings.searchHistory
            if (h.isNotEmpty()) {
                T("Recent searches", 14, Theme.accent, modifier = Modifier.padding(top = 10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(h) { s -> Box(Modifier.tvItem(onMenu = { app.update { it.copy(searchHistory = SearchHistory.remove(it.searchHistory, s)) } }) { q = s }.background(Theme.surface, RoundedCornerShape(14.dp)).padding(12.dp, 6.dp)) { T(s, 13) } }
                    item { Box(Modifier.tvItem { app.update { it.copy(searchHistory = emptyList()) } }.padding(12.dp, 6.dp)) { T("Clear", 13, Theme.dim) } }
                }
            }
            return@Column
        }
        if (res.isEmpty) { T(if (app.busy) "The index is being built…" else "No results", 14, Theme.dim, modifier = Modifier.padding(top = 10.dp)); return@Column }
        val now = System.currentTimeMillis() / 1000
        val progs = remember(res) {
            ProgGroups.group(res.programmes.map { it.copy(start = it.start + sh, stop = it.stop + sh) }, { byEpg[it] }, { c, h -> ProgTime.classify(c, h.start, h.stop, now) }, { it.key })
        }
        LazyColumn {
            if (res.movies.isNotEmpty()) item {
                T("Movies", 15, Theme.accent, modifier = Modifier.padding(top = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(res.movies, key = { it.key }) { h -> val v = h.toVod(); PosterCard(v, app, 130.dp) { keep(); app.detail = v } } }
            }
            if (res.series.isNotEmpty()) item {
                T("Shows", 15, Theme.accent, modifier = Modifier.padding(top = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(res.series, key = { it.key }) { h -> val v = h.toVod(); PosterCard(v, app, 130.dp) { keep(); app.detail = v } } }
            }
            val chs = res.channels.mapNotNull { byKey[it.key] }
            if (chs.isNotEmpty()) item {
                T("Channels", 15, Theme.accent, modifier = Modifier.padding(top = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(chs, key = { it.key }) { c -> Column(Modifier.width(160.dp).tvItem(onMenu = { app.channelMenu(c) }) { keep(); app.playChannel(c) }.padding(6.dp)) {
                        NetImage(c.logo, Modifier.size(56.dp, 34.dp)); T(c.name, 13, bold = true); T(app.epg.nowNext(c, now).first?.title ?: "", 12, Theme.dim)
                    } }
                }
            }
            if (progs.isNotEmpty()) item { T("Programmes", 15, Theme.accent, modifier = Modifier.padding(top = 8.dp)) }
            items(progs, key = { "p" + it.first.key }) { (c, hits) ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    T(c.name, 13, Theme.dim)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(hits.take(20)) { h ->
                            val p = Programme(c.idKey ?: c.nameKey, h.start, h.stop, h.title, null, h.sub, h.ep)
                            val st = ProgTime.classify(c, h.start, h.stop, now)
                            Column(Modifier.width(240.dp).tvItem(onMenu = { keep(); app.programmeMenu(c, p) }) {
                                keep()
                                when (st) { ProgState.NOW -> app.playChannel(c); ProgState.PAST_CATCHUP -> app.playArchive(c, p); else -> app.programmeMenu(c, p) }
                            }.background(Theme.surface, RoundedCornerShape(6.dp)).padding(8.dp)) {
                                T(h.title, 14, bold = true)
                                EpisodeNum.line(h.ep, h.sub)?.let { T(it, 12) }
                                when (st) {
                                    ProgState.NOW -> { T("On now · ${ProgTime.minutesLeft(h.stop, now)} min left", 12, Theme.live); ProgressLine(ProgTime.progress(h.start, h.stop, now), Modifier.fillMaxWidth().padding(top = 2.dp)) }
                                    ProgState.PAST_CATCHUP -> T("⟲ Catch-up · ${TimeFmt.dayTime(h.start * 1000, "EEE")}", 12, Theme.accent)
                                    else -> T(TimeFmt.dayTime(h.start * 1000, "EEE d MMM") + if (app.reminderFor(c, p) != null) "  ⏰" else "", 12, Theme.dim)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
