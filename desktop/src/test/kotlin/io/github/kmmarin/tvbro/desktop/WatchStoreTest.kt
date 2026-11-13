// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Test
import io.github.kmmarin.tvbro.data.*
import java.io.File
import java.nio.file.Files

class WatchStoreTest {
    private var t = 1_000L
    private fun store(f: File = File(Files.createTempDirectory("tvbro-w").toFile(), "watch.json")) = WatchStore(f, clock = { t++ })
    private val show = VodItem("s|src|9", "src", "series", "9", "Show", null, "Drama", null, "")
    private fun ep(id: String, s: Int, n: Int) = Episode(id, s, n, "Show - S0${s}E0$n - Title $n", "mkv", null, "45:00")
    private val eps = listOf(ep("1", 1, 1), ep("2", 1, 2), ep("3", 2, 1))
    private fun k(e: Episode) = WatchStore.epKey(show, e)

    @Test fun continueWatchingFromTheFirstSecond() {
        val w = store()
        w.updateSeries(show, eps, lastId = "1", touch = true) // playback started, nothing saved yet
        val c = w.continueShows()
        assertEquals(1, c.size)
        assertEquals("1", c[0].second.nextEp)
    }

    @Test fun watchedAt90PercentMovesToNextEpisodeAcrossSeasons() {
        val w = store()
        w.updateSeries(show, eps, lastId = "2")
        assertTrue(w.setPos(k(eps[1]), 41 * 60_000L, 45 * 60_000L, show.key))
        w.updateSeries(show, eps, lastId = "2")
        val r = w.series(show.key)!!
        assertEquals("3", r.nextEp); assertEquals(2, r.nextSeason); assertEquals(1, r.nextNum)
        assertEquals("Title 1", r.nextTitle) // cleaned
        assertEquals(0L, w.state(k(eps[1]))!!.posMs)
    }

    @Test fun perEpisodeResume() {
        val w = store()
        assertFalse(w.setPos(k(eps[0]), 12 * 60_000L + 31_000, 45 * 60_000L, show.key))
        val s = w.state(k(eps[0]))!!
        assertTrue(s.inProgress); assertEquals("12:31", Watch.clock(s.posMs))
        assertNull(w.state(k(eps[1])))
    }

    @Test fun finishedShowDropsOffAndUnmarkingAllForgetsIt() {
        val w = store()
        w.updateSeries(show, eps, lastId = "1")
        w.markEpisodes(show, eps, eps, true)
        assertTrue(w.series(show.key)!!.finished)
        assertTrue(w.continueShows().isEmpty())
        w.markEpisodes(show, eps, eps, false)
        assertNull(w.series(show.key)!!.lastEp)
        assertFalse(w.series(show.key)!!.active)
    }

    @Test fun markSeasonWatched() {
        val w = store()
        w.markEpisodes(show, eps, eps.filter { it.season == 1 }, true)
        assertTrue(w.state(k(eps[0]))!!.watched && w.state(k(eps[1]))!!.watched)
        assertNull(w.state(k(eps[2])))
        assertEquals("3", w.series(show.key)!!.nextEp)
    }

    @Test fun captionsAndAudioRememberedPerSeries() {
        val w = store()
        w.rememberSubs(show.key, true, "fre")
        w.rememberAudio(show.key, "eng")
        val r = w.series(show.key)!!
        assertEquals(TrackChoice.Pick(1), Watch.pickText(r.subPref, listOf(TrackInfo("en"), TrackInfo("fra"))))
        assertEquals(0, Watch.pickAudio(r.audioLang, listOf(TrackInfo("en-US"), TrackInfo("de"))))
        w.rememberSubs(show.key, false, null)
        assertEquals(TrackChoice.Off, Watch.pickText(w.series(show.key)!!.subPref, listOf(TrackInfo("en"))))
    }

    @Test fun survivesARestartAndImportsTheOldResumeMap() {
        val f = File(Files.createTempDirectory("tvbro-w").toFile(), "watch.json")
        val a = store(f)
        val movie = VodItem("m|src|5", "src", "movie", "5", "Film", null, "Films", null, "mp4")
        a.snap(movie)
        a.setPos(movie.key, 600_000, 6_000_000)
        a.importLegacy(mapOf("m|src|6" to 120L, movie.key to 1L)) // existing entries win
        a.save()
        val b = store(f)
        assertEquals(600_000L, b.state(movie.key)!!.posMs)
        assertEquals(120_000L, b.state("m|src|6")!!.posMs)
        assertEquals(listOf("Film"), b.continueMovies().map { it.first.name })
    }

    // ---- H6: saved catalogue copies keep no stream address ----
    @Test fun snapshotsAreSavedWithoutStreamAddresses() {
        val f = File(Files.createTempDirectory("tvbro-w").toFile(), "watch.json")
        val w = store(f)
        val v = VodItem("v|src|9", "src", "movie", "9", "Film", null, "Films", null, "", "http://tv.example/movie/alice/s3cret/9.mkv")
        w.snap(v); w.updateSeries(show.copy(url = "http://tv.example/series/alice/s3cret/1.mkv"), eps, lastId = "1"); w.save()
        assertFalse(f.readText().contains("s3cret"))
        assertNull(w.snapOf(v.key)!!.url)
        assertEquals("Film", store(f).snapOf(v.key)!!.name)
        w.replace(WatchData(snaps = mapOf(v.key to v))) // a backup restore too
        assertNull(w.snapOf(v.key)!!.url)
    }

    @Test fun anOlderFileWithAddressesIsRewrittenWithoutThem() {
        val f = File(Files.createTempDirectory("tvbro-w").toFile(), "watch.json")
        val v = VodItem("v|src|9", "src", "movie", "9", "Film", null, "Films", null, "", "http://tv.example/movie/alice/s3cret/9.mkv")
        f.writeText(Json2.json.encodeToString(WatchData.serializer(), WatchData(snaps = mapOf(v.key to v))))
        val w = store(f)
        assertNull(w.snapOf(v.key)!!.url)
        assertFalse(f.readText().contains("s3cret"))
    }
}