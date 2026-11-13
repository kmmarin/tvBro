// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Test

/** M6 (external player argument injection) and L9 (what mpv may open, hardening options). */
class PlayUrlTest {
    @Test fun networkStreamsPass() {
        for (u in listOf("http://tv.example/live/u/p/1.ts", "HTTPS://tv.example/a.m3u8?token=x", "rtmp://tv.example/live/a", "rtmps://x/y",
            "rtsp://cam.lan:554/s", "rtp://239.0.0.1:5000", "udp://@239.1.1.1:1234", "srt://tv.example:9000?mode=caller", "http://127.0.0.1:40123/smb/abc"))
            assertTrue(u, PlayUrl.allowed(u))
    }

    @Test fun optionsLocalFilesAndMpvProtocolsAreRefused() {
        for (u in listOf("--script=/tmp/x.lua", "-v", "--ytdl-raw-options=exec=x", "file:///etc/passwd", "edl://a.mkv,0,10", "av://lavfi:sine",
            "lavfi://sine", "memory://abc", "ytdl://x", "fd://3", "fdclose://3", "smb://nas/share/a.ts", "data:text/plain,x", "concat:a|b",
            "/etc/passwd", "C:\\Windows\\win.ini", "http://ok.example/\n--script=x", "http://a b", "", "ftp://x/y", "javascript:alert(1)"))
            assertFalse(u, PlayUrl.allowed(u))
    }

    @Test fun localRecordingsOnlyWhenTvBroMadeTheAddress() {
        assertTrue(PlayUrl.allowed("/tmp/tvbro-test/Videos/tvBro/News 2026-11-12 18-00.ts", trustedLocal = true))
        assertTrue(PlayUrl.allowed("C:\\tvbro-test\\Videos\\tvBro\\News.ts", trustedLocal = true))
        assertTrue(PlayUrl.allowed("\\\\nas\\share\\News.ts", trustedLocal = true))
        assertFalse(PlayUrl.allowed("/tmp/tvbro-test/x.ts"))
        assertFalse(PlayUrl.allowed("-x.ts", trustedLocal = true))
        assertFalse(PlayUrl.allowed("edl:///tmp/a", trustedLocal = true))
        assertFalse(PlayUrl.allowed("relative/path.ts", trustedLocal = true))
    }

    @Test fun refusalNeverShowsTheAddress() {
        val m = PlayUrl.refusal("file:///tmp/tvbro-test/secret-user/secret-pass")
        assertFalse(m.contains("tvbro-test")); assertFalse(m.contains("secret"))
        assertTrue(PlayUrl.refusal("--script=x").contains("option"))
    }

    @Test fun externalPlayerGetsDoubleDashBeforeTheAddress() {
        val u = "http://tv.example/live/1.ts"
        assertEquals(listOf("vlc", "--", u), ExternalPlayer.command("vlc", u) { false })
        assertEquals(listOf("mpv", "--fs", "--", u), ExternalPlayer.command("mpv --fs", u) { false })
        assertEquals(listOf("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe", "--", u), ExternalPlayer.command("C:\\Program Files\\VideoLAN\\VLC\\vlc.exe", u) { true })
        assertEquals(listOf("/usr/bin/mpv", "--", u), ExternalPlayer.command("/usr/bin/mpv", u) { true })
        assertEquals(listOf("totem", u), ExternalPlayer.command("totem", u) { false }) // unknown player: the address is checked, no "--"
    }

    @Test fun externalPlayerRefusesOptionsAndNonNetworkAddresses() {
        assertNull(ExternalPlayer.command("vlc", "--extraintf=lua") { false })
        assertNull(ExternalPlayer.command("mpv", "--script=/tmp/evil.lua") { false })
        assertNull(ExternalPlayer.command("vlc", "file:///etc/shadow") { false })
        assertNull(ExternalPlayer.command("", "http://tv.example/1.ts") { false })
    }

    @Test fun mpvRunsNothingButThePlayback() {
        val o = MpvHardening.options.toMap()
        assertEquals("no", o["ytdl"]); assertEquals("no", o["load-scripts"]); assertEquals("no", o["config"])
        assertEquals("no", o["load-stats-overlay"]); assertEquals("no", o["load-unsafe-playlists"]); assertEquals("", o["input-ipc-server"])
        val wl = o["demuxer-lavf-o"]!!
        assertTrue(wl.startsWith("protocol_whitelist=[") && wl.endsWith("]"))
        val protos = MpvHardening.PROTOCOLS.split(',')
        for (p in listOf("file", "concat", "subfile", "pipe", "fd", "ftp", "smb", "sftp", "gopher", "unix")) assertFalse(p, p in protos)
        for (p in listOf("http", "https", "tls", "tcp", "hls", "crypto")) assertTrue(p, p in protos)
    }
}
