// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import androidx.compose.runtime.mutableStateOf
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import kotlinx.serialization.json.*
import io.github.kmmarin.tvbro.data.Diag
import java.io.File
import java.io.RandomAccessFile

/** Minimal JNA binding of the public libmpv client API (client.h). */
@Suppress("FunctionName")
interface MpvLib : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(h: Pointer): Int
    fun mpv_terminate_destroy(h: Pointer)
    fun mpv_set_option_string(h: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(h: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(h: Pointer, name: String): Pointer?
    fun mpv_free(p: Pointer)
    fun mpv_command(h: Pointer, args: Array<String?>): Int
    fun mpv_wait_event(h: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(h: Pointer)
    fun mpv_error_string(err: Int): String
    fun mpv_client_api_version(): NativeLong
}

/** Format and processor of a native library file, read from its PE (Windows) or ELF (Linux) header. */
data class BinArch(val format: String, val arch: String) {
    companion object {
        /** Null when [f] is neither a PE nor an ELF file (or cannot be read). */
        fun of(f: File): BinArch? = try {
            RandomAccessFile(f, "r").use { r ->
                if (r.length() < 64) return null
                val h = ByteArray(64).also { r.readFully(it) }
                fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
                fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
                when {
                    h[0] == 'M'.code.toByte() && h[1] == 'Z'.code.toByte() -> {
                        val off = le32(h, 0x3C).toLong()
                        if (off < 0 || off + 6 > r.length()) return null
                        r.seek(off)
                        val pe = ByteArray(6).also { r.readFully(it) }
                        if (pe[0] != 'P'.code.toByte() || pe[1] != 'E'.code.toByte() || pe[2] != 0.toByte() || pe[3] != 0.toByte()) return null
                        BinArch("pe", when (le16(pe, 4)) { 0x8664 -> "x64"; 0x14c -> "x86"; 0xAA64 -> "arm64"; 0x1c4 -> "arm"; else -> "other" })
                    }
                    h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() ->
                        BinArch("elf", when (le16(h, 18)) { 0x3E -> "x64"; 0x03 -> "x86"; 0xB7 -> "arm64"; 0x28 -> "arm"; else -> "other" })
                    else -> null
                }
            }
        } catch (e: java.io.IOException) { null }

        /** The processor this JVM runs on, in [BinArch.arch] terms. */
        fun jvm(): String = when (val a = System.getProperty("os.arch").lowercase()) {
            "amd64", "x86_64" -> "x64"; "aarch64", "arm64" -> "arm64"; "x86", "i386", "i486", "i586", "i686" -> "x86"; "arm" -> "arm"; else -> a
        }

        fun label(a: String) = when (a) {
            "x64" -> "64-bit PCs (x86_64)"; "x86" -> "32-bit PCs (i686)"; "arm64" -> "64-bit ARM (aarch64)"; "arm" -> "32-bit ARM"; else -> "another processor"
        }
    }
}

/** A place libmpv may be loaded from: a file ([file], checked before loading) or a library name the OS searches for. */
data class MpvCandidate(val path: String, val file: Boolean, val chosen: Boolean = false)

/** Result of a load: the library, or why there is none (a sentence for the user). */
data class MpvLoad(val lib: MpvLib?, val error: String?, val from: String?)

/**
 * Where libmpv is looked for and why it could not be loaded. tvBro does not ship libmpv (the usual Windows builds are
 * GPL): the user's own copy is used, from a file they chose, next to tvBro.exe, or wherever the OS finds it.
 */
object MpvLocate {
    const val WIN_DLL = "libmpv-2.dll"

    fun candidates(windows: Boolean, chosen: String?, appDir: String?): List<MpvCandidate> = buildList {
        chosen?.takeIf { it.isNotBlank() }?.let { add(MpvCandidate(it, file = true, chosen = true)) }
        if (windows) {
            appDir?.let { add(MpvCandidate(File(it, WIN_DLL).path, file = true)) }
            for (n in listOf("libmpv-2", "mpv-2", "mpv")) add(MpvCandidate(n, file = false))
        } else for (n in listOf("mpv", "libmpv.so.2", "libmpv.so.1")) add(MpvCandidate(n, file = false))
    }

    /** Checks a file before it is loaded: null = it may be loaded, else why not. */
    fun problem(f: File, windows: Boolean, jvmArch: String): String? {
        if (!f.isFile) return "${f.path} is not there (moved or deleted?)"
        val kind = if (windows) "a Windows DLL" else "a Linux shared library"
        val b = BinArch.of(f) ?: return "${f.name} is not $kind"
        if (b.format != (if (windows) "pe" else "elf")) return "${f.name} is not $kind"
        if (b.arch != jvmArch) return "${f.name} is built for ${BinArch.label(b.arch)}; this computer needs the build for ${BinArch.label(jvmArch)}"
        return null
    }

    /**
     * Tries [cands] in order with [open]. A file next to the app that is simply absent is skipped quietly; a chosen
     * file that is gone, of the wrong kind or processor is reported first, then problems of the file next to the app.
     */
    fun load(cands: List<MpvCandidate>, windows: Boolean, jvmArch: String, open: (String) -> MpvLib): MpvLoad {
        var chosenErr: String? = null
        var fileErr: String? = null
        for (c in cands) {
            if (c.file) {
                val f = File(c.path)
                if (!c.chosen && !f.exists()) continue
                val p = problem(f, windows, jvmArch)
                if (p != null) { if (c.chosen) chosenErr = chosenErr ?: p else fileErr = fileErr ?: p; continue }
            }
            try { return MpvLoad(open(c.path), null, c.path) } catch (e: Exception) {
                if (c.file) { val m = "${File(c.path).name} could not be loaded: ${firstLine(e)}"; if (c.chosen) chosenErr = chosenErr ?: m else fileErr = fileErr ?: m }
            } catch (e: LinkageError) {
                if (c.file) { val m = "${File(c.path).name} could not be loaded: ${firstLine(e)}"; if (c.chosen) chosenErr = chosenErr ?: m else fileErr = fileErr ?: m }
            }
        }
        val none = if (windows) "$WIN_DLL was not found next to tvBro.exe or on PATH" else "libmpv was not found (Debian / Raspberry Pi OS: sudo apt install libmpv2)"
        return MpvLoad(null, chosenErr ?: fileErr ?: none, null)
    }

    private fun firstLine(e: Throwable) = e.message?.lineSequence()?.firstOrNull()?.trim()?.ifBlank { null } ?: e.javaClass.simpleName
}

/**
 * The libmpv in use. Loaded on first use; [reload] tries again (after the user added the file), [choose] loads a file
 * the user picked and remembers it (`libmpv-path.txt` in the config folder). Nothing is ever downloaded.
 */
object MpvLoader {
    private val loaded = mutableStateOf<MpvLib?>(null) // Compose state: the video surface appears once it is set
    @Volatile private var tried = false
    @Volatile var error: String? = null
        private set
    /** Where the loaded library came from (a path or a library name). */
    @Volatile var from: String? = null
        private set
    val appDir: String? get() = System.getProperty("tvbro.home") ?: System.getProperty("jpackage.app-path")?.let { File(it).parent }
    private val pathFile: File get() = File(Paths.config, "libmpv-path.txt")
    /** The file the user chose earlier, or null. */
    val chosen: String? get() = try { pathFile.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null } } catch (e: Exception) { null }

    val lib: MpvLib? get() = if (tried) loaded.value else reload()

    @Synchronized fun reload(): MpvLib? = attempt(chosen)

    /** Loads [f] (picked by the user) and remembers it. Null = loaded, else why not; a file that fails is not remembered. */
    @Synchronized fun choose(f: File): String? {
        MpvLocate.problem(f, Platform.windows, BinArch.jvm())?.let { return it }
        if (attempt(f.absolutePath) == null) return error
        if (from == f.absolutePath) try { atomicWrite(pathFile, f.absolutePath.toByteArray(Charsets.UTF_8)) } catch (e: Exception) { Diag.log("libmpv path not saved") }
        return null
    }

    private fun attempt(pick: String?): MpvLib? {
        loaded.value?.let { return it } // a loaded library stays loaded for the whole run
        tried = true
        appDir?.let { System.setProperty("jna.library.path", it) }
        val r = MpvLocate.load(MpvLocate.candidates(Platform.windows, pick, appDir), Platform.windows, BinArch.jvm(), ::open)
        error = r.error; from = r.from; loaded.value = r.lib
        if (r.lib == null) Diag.log("libmpv not found: ${r.error}")
        return r.lib
    }

    /** Loads [path] and checks it really is libmpv (it has the client API entry point). */
    fun open(path: String): MpvLib {
        val lib = Native.load(path, MpvLib::class.java)
        val v = try { lib.mpv_client_api_version().toLong() } catch (e: UnsatisfiedLinkError) { throw IllegalStateException("this is not libmpv") }
        check(v ushr 16 >= 1) { "unknown libmpv client API version $v" }
        return lib
    }
}

/**
 * Options for every instance: stream addresses come from the provider, so mpv runs nothing beyond playing them. No
 * youtube-dl hook, no Lua / JS scripts, no mpv.conf or watch-later files from the user's folders, no OSC / stats /
 * console scripts, no IPC server; nested playlists may not point at unsafe protocols, and FFmpeg may open only
 * network protocols (plus the HLS / crypto layers they need).
 */
object MpvHardening {
    const val PROTOCOLS = "http,https,tcp,tls,crypto,hls,httpproxy,rtmp,rtmps,rtmpt,rtmpts,rtsp,rtp,udp,srt,data"
    val options: List<Pair<String, String>> = listOf(
        "ytdl" to "no", "load-scripts" to "no", "config" to "no", "load-stats-overlay" to "no",
        "load-console" to "no", "load-auto-profiles" to "no", "load-select" to "no", "load-positioning" to "no", "load-commands" to "no",
        "osc" to "no", "input-ipc-server" to "", "use-filedir-conf" to "no",
        "save-position-on-quit" to "no", "write-filename-in-watch-later-config" to "no", "resume-playback" to "no",
        "load-unsafe-playlists" to "no", // access-references stays on: HLS opens its segments through it (the whitelist below limits them)
        "demuxer-lavf-o" to "protocol_whitelist=[$PROTOCOLS]", "stream-lavf-o" to "protocol_whitelist=[$PROTOCOLS]",
    )
}

data class Track(val id: String, val type: String, val title: String, val lang: String?, val selected: Boolean, val w: Int = 0, val h: Int = 0, val bitrate: Long = 0)

/**
 * One mpv instance: drawing into the native window [wid], headless (wid 0, self-test), or a [recorder] that only
 * demuxes and writes `stream-record` (no decoding, no output). [onEnd] gets "eof" or "error: ..." (any thread);
 * [onLoaded] fires when a file is loaded and its tracks are known.
 */
class Mpv(private val lib: MpvLib, wid: Long, hwdec: String, recorder: Boolean = false, private val onLoaded: () -> Unit = {}, onEnd: (String) -> Unit) {
    private var h: Pointer? = lib.mpv_create() ?: error("mpv_create failed")
    @Volatile private var alive = true
    private val events: Thread

    init {
        val o = { k: String, v: String -> lib.mpv_set_option_string(h!!, k, v) }
        if (wid > 0) {
            o("wid", wid.toString())
            if (!Platform.windows) o("gpu-context", "x11egl") // AWT windows are X11 (XWayland under labwc)
            o("vo", "gpu")
        } else { o("vo", "null"); o("ao", "null") } // headless self-test or recorder
        if (recorder) { o("vd-lavc-skipframe", "all"); o("vd-lavc-skipidct", "all"); o("vd-lavc-skiploopfilter", "all"); o("sid", "no"); o("network-timeout", "30") }
        o("hwdec", hwdec)
        System.getenv("TVBRO_MPV_LOG")?.let { o("log-file", it) } // diagnostics only; the log can contain stream URLs
        o("osc", "no"); o("input-default-bindings", "no"); o("input-vo-keyboard", "no"); o("input-cursor", "no")
        o("cursor-autohide", "no"); o("keep-open", "no"); o("idle", "yes")
        o("cache", "yes"); o("demuxer-max-bytes", "64MiB"); o("demuxer-readahead-secs", "20")
        if (!recorder) o("network-timeout", "20")
        o("user-agent", "tvBro/0.1"); o("sub-auto", "fuzzy")
        o("osd-font-size", "28"); o("osd-bar", "yes"); o("terminal", "no")
        for ((k, v) in MpvHardening.options) o(k, v)
        val r = lib.mpv_initialize(h!!)
        if (r < 0) error("mpv_initialize: ${lib.mpv_error_string(r)}")
        events = Thread({
            while (alive) {
                val hh = h ?: break
                val ev = lib.mpv_wait_event(hh, 0.5)
                if (!alive) break
                when (ev.getInt(0)) { // event_id
                    1 -> break // MPV_EVENT_SHUTDOWN
                    7 -> { // MPV_EVENT_END_FILE: data -> mpv_event_end_file { int reason; int error; }
                        val d = ev.getPointer(16)
                        val reason = d?.getInt(0) ?: 0
                        val err = d?.getInt(4) ?: 0
                        if (reason == 4) onEnd("error: ${lib.mpv_error_string(err)}") else if (reason == 0) onEnd("eof")
                    }
                    8 -> onLoaded() // MPV_EVENT_FILE_LOADED
                }
            }
        }, "mpv-events").apply { isDaemon = true; start() }
    }

    fun cmd(vararg a: String) { h?.let { lib.mpv_command(it, arrayOf(*a, null)) } }
    fun set(k: String, v: String) { h?.let { lib.mpv_set_property_string(it, k, v) } }
    fun get(k: String): String? {
        val p = lib.mpv_get_property_string(h ?: return null, k) ?: return null
        return try { p.getString(0, "UTF-8") } finally { lib.mpv_free(p) }
    }
    fun num(k: String): Double? = get(k)?.toDoubleOrNull()

    /**
     * Loads [url]. Only network streams, or a local file when tvBro made the address ([trustedLocal]: recordings);
     * anything else (file://, edl://, lavfi, options) throws [IllegalArgumentException] and nothing is loaded.
     */
    fun play(url: String, startSec: Long = 0, trustedLocal: Boolean = false) {
        require(PlayUrl.allowed(url, trustedLocal)) { PlayUrl.refusal(url) }
        set("start", if (startSec > 0) "+$startSec" else "none")
        cmd("loadfile", url, "replace")
    }

    fun tracks(): List<Track> = try {
        Json.parseToJsonElement(get("track-list") ?: "[]").jsonArray.map {
            val o = it.jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            Track(s("id") ?: "", s("type") ?: "", s("title") ?: s("codec") ?: "", s("lang"), s("selected") == "true",
                s("demux-w")?.toIntOrNull() ?: 0, s("demux-h")?.toIntOrNull() ?: 0, (s("hls-bitrate") ?: s("demux-bitrate"))?.toDoubleOrNull()?.toLong() ?: 0)
        }
    } catch (e: Exception) { emptyList() }

    /** ASS text drawn by mpv's own OSD on top of the video (works the same with any window system). */
    fun overlay(id: Int, ass: String?) {
        if (ass == null) cmd("osd-overlay", id.toString(), "none", "") else cmd("osd-overlay", id.toString(), "ass-events", ass, "0", "720")
    }

    /** Stops the event thread first (it must not wait on a destroyed handle), then destroys the instance. */
    fun destroy() {
        val hh = h ?: return
        alive = false
        lib.mpv_wakeup(hh)
        if (Thread.currentThread() !== events) events.join(2000)
        h = null
        lib.mpv_terminate_destroy(hh)
    }
}

object Platform {
    val windows = System.getProperty("os.name").lowercase().contains("win")
}
