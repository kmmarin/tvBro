// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

/** The libmpv loader fallback: missing, wrong-processor and wrong-kind files never crash and say what is wrong. */
class MpvLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fakeLib = Proxy.newProxyInstance(MpvLib::class.java.classLoader, arrayOf(MpvLib::class.java)) { _, _, _ -> null } as MpvLib

    /** A minimal PE file (DOS header + "PE\0\0" + machine). */
    private fun pe(name: String, machine: Int): File = tmp.newFile(name).apply {
        val b = ByteArray(256)
        b[0] = 'M'.code.toByte(); b[1] = 'Z'.code.toByte()
        b[0x3C] = 0x80.toByte()
        b[0x80] = 'P'.code.toByte(); b[0x81] = 'E'.code.toByte()
        b[0x84] = (machine and 0xff).toByte(); b[0x85] = (machine shr 8).toByte()
        writeBytes(b)
    }

    /** A minimal little-endian ELF header with e_machine. */
    private fun elf(name: String, machine: Int): File = tmp.newFile(name).apply {
        val b = ByteArray(64)
        b[0] = 0x7f; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte(); b[4] = 2; b[5] = 1
        b[18] = (machine and 0xff).toByte(); b[19] = (machine shr 8).toByte()
        writeBytes(b)
    }

    private val tried = mutableListOf<String>()
    private val nothingLoads: (String) -> MpvLib = { tried += it; throw UnsatisfiedLinkError("Unable to load library '$it'") }

    @Test fun headerArch() {
        assertEquals(BinArch("pe", "x64"), BinArch.of(pe("a.dll", 0x8664)))
        assertEquals(BinArch("pe", "x86"), BinArch.of(pe("b.dll", 0x14c)))
        assertEquals(BinArch("pe", "arm64"), BinArch.of(pe("c.dll", 0xAA64)))
        assertEquals(BinArch("elf", "arm64"), BinArch.of(elf("libmpv.so.2", 0xB7)))
        assertNull(BinArch.of(tmp.newFile("text.dll").apply { writeText("not a library at all, just some text in a file .......................") }))
        assertNull(BinArch.of(tmp.newFile("short.dll").apply { writeBytes(byteArrayOf(0x4D, 0x5A)) }))
    }

    @Test fun nothingAnywhere() {
        val app = tmp.newFolder("app").path
        val r = MpvLocate.load(MpvLocate.candidates(true, null, app), true, "x64", nothingLoads)
        assertNull(r.lib)
        assertEquals("libmpv-2.dll was not found next to tvBro.exe or on PATH", r.error)
        assertEquals(listOf("libmpv-2", "mpv-2", "mpv"), tried) // the absent file next to the app is not even tried
    }

    @Test fun chosenFileGone() {
        val gone = File(tmp.root, "moved/libmpv-2.dll").path
        val r = MpvLocate.load(MpvLocate.candidates(true, gone, null), true, "x64", nothingLoads)
        assertNull(r.lib)
        assertTrue(r.error!!, r.error!!.contains(gone) && r.error!!.contains("is not there"))
        assertFalse(gone in tried)
    }

    @Test fun wrongArchIsRefusedBeforeLoading() {
        val x86 = pe("libmpv-2.dll", 0x14c)
        val r = MpvLocate.load(MpvLocate.candidates(true, x86.path, null), true, "x64", nothingLoads)
        assertNull(r.lib)
        assertTrue(r.error!!, r.error!!.contains("32-bit") && r.error!!.contains("x86_64"))
        assertFalse(x86.path in tried)
        val arm = pe("arm.dll", 0xAA64)
        assertTrue(MpvLocate.problem(arm, true, "x64")!!.contains("aarch64"))
        assertNull(MpvLocate.problem(pe("ok.dll", 0x8664), true, "x64"))
    }

    @Test fun wrongKindOfFile() {
        val txt = tmp.newFile("libmpv-2.dll.txt").apply { writeText("x".repeat(100)) }
        assertEquals("libmpv-2.dll.txt is not a Windows DLL", MpvLocate.problem(txt, true, "x64"))
        assertEquals("a.dll is not a Linux shared library", MpvLocate.problem(pe("a.dll", 0xB7), false, "arm64"))
        assertTrue(MpvLocate.problem(elf("libmpv.so.2", 0x3E), false, "arm64")!!.contains("x86_64"))
        assertTrue(MpvLocate.load(MpvLocate.candidates(false, null, null), false, "arm64", nothingLoads).error!!.contains("apt install libmpv2"))
    }

    @Test fun badFileNextToAppFallsBackToPath() {
        val app = tmp.newFolder("app2")
        val dll = File(app, "libmpv-2.dll").apply { writeBytes(pe("x.dll", 0x14c).readBytes()) }
        val r = MpvLocate.load(MpvLocate.candidates(true, null, app.path), true, "x64") { if (it == "mpv-2") fakeLib else nothingLoads(it) }
        assertSame(fakeLib, r.lib)
        assertEquals("mpv-2", r.from)
        assertFalse(dll.path in tried)
        // nothing on PATH either: the reason given is the file next to the app, not "not found"
        val r2 = MpvLocate.load(MpvLocate.candidates(true, null, app.path), true, "x64", nothingLoads)
        assertTrue(r2.error!!, r2.error!!.startsWith("libmpv-2.dll is built for 32-bit"))
    }

    @Test fun chosenFileWinsAndLoadErrorsAreReported() {
        val app = tmp.newFolder("app3")
        File(app, "libmpv-2.dll").writeBytes(pe("y.dll", 0x8664).readBytes())
        val mine = pe("mine.dll", 0x8664)
        val r = MpvLocate.load(MpvLocate.candidates(true, mine.path, app.path), true, "x64") { tried += it; fakeLib }
        assertEquals(mine.path, r.from)
        assertEquals(listOf(mine.path), tried)
        // a right-looking file the OS refuses (e.g. a missing dependency): its own message, never an exception
        val r2 = MpvLocate.load(MpvLocate.candidates(true, mine.path, null), true, "x64") { throw UnsatisfiedLinkError("The specified module could not be found.\r\nmore") }
        assertNull(r2.lib)
        assertEquals("mine.dll could not be loaded: The specified module could not be found.", r2.error)
        val r3 = MpvLocate.load(MpvLocate.candidates(true, mine.path, null), true, "x64") { throw IllegalStateException("this is not libmpv") }
        assertEquals("mine.dll could not be loaded: this is not libmpv", r3.error)
    }

    // ---- with the real JNA loader (Windows) ----

    @Test fun realWrongArchDll() {
        val wow = File(System.getenv("SystemRoot") ?: "C:\\Windows", "SysWOW64\\version.dll")
        assumeTrue(Platform.windows && BinArch.jvm() == "x64" && wow.isFile)
        val r = MpvLocate.load(listOf(MpvCandidate(wow.path, file = true, chosen = true)), true, "x64", MpvLoader::open)
        assertNull(r.lib)
        assertTrue(r.error!!, r.error!!.contains("32-bit"))
    }

    @Test fun realDllThatIsNotLibmpv() {
        val sys = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\version.dll")
        assumeTrue(Platform.windows && sys.isFile)
        val r = MpvLocate.load(listOf(MpvCandidate(sys.path, file = true, chosen = true)), true, BinArch.jvm(), MpvLoader::open)
        assertNull(r.lib)
        assertEquals("version.dll could not be loaded: this is not libmpv", r.error)
    }

    /** Only where TVBRO_TEST_LIBMPV points at a real libmpv-2.dll (never part of the repo or the download). */
    @Test fun realLibmpv() {
        val f = System.getenv("TVBRO_TEST_LIBMPV")?.let(::File)
        assumeTrue(f != null && f.isFile)
        val r = MpvLocate.load(listOf(MpvCandidate(f!!.path, file = true, chosen = true)), Platform.windows, BinArch.jvm(), MpvLoader::open)
        assertNotNull(r.error, r.lib)
        assertTrue(r.lib!!.mpv_client_api_version().toLong() ushr 16 >= 2)
    }
}
