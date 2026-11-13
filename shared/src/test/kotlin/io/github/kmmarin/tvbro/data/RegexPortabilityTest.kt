// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, which is stricter than the JVM: a "{" or "}" that is not part of a quantifier
 * must be escaped. The JVM accepts `\{(\w+)}` but Android throws PatternSyntaxException in the static initializer.
 * This test finds every Regex literal in the app, shared and desktop sources, compiles it, and applies the ICU rule.
 */
class RegexPortabilityTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private val raw = Regex("Regex\\(\"\"\"(.*?)\"\"\"")
    private val plain = Regex("Regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun unescapeKotlin(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) { 'n' -> append('\n'); 't' -> append('\t'); '$' -> append('$'); else -> append(n) }
                i += 2
            } else { append(c); i++ }
        }
    }

    /** Problems under ICU rules: unescaped braces outside character classes that are not a {n}, {n,} or {n,m} quantifier. */
    fun icuProblems(p: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> {
                    // \p{..}, \P{..}, \x{..}, \N{..} carry their own braces
                    if (p.getOrNull(i + 1) in setOf('p', 'P', 'x', 'N') && p.getOrNull(i + 2) == '{') {
                        val end = p.indexOf('}', i + 3)
                        if (end < 0) { out += "unterminated \\${p[i + 1]}{ at $i"; break }
                        i = end + 1
                    } else i += 2
                    continue
                }
                inClass -> if (c == ']') inClass = false
                c == '[' -> { inClass = true; if (p.getOrNull(i + 1) == ']') i++ }
                c == '{' -> {
                    val m = Regex("""\{\d+(,\d*)?\}""").matchAt(p, i)
                    if (m == null) out += "unescaped { at $i" else { i = m.range.last + 1; continue }
                }
                c == '}' -> out += "unescaped } at $i"
            }
            i++
        }
        return out
    }

    @Test fun ruleCatchesTheCrash() {
        assertTrue(icuProblems("""\$?\{(\w+)}""").isNotEmpty())
        assertTrue(icuProblems("""\$?\{(\w+)\}""").isEmpty())
        assertTrue(icuProblems("""\d{1,2}[{}]x{3}\p{M}+""").isEmpty())
    }

    @Test fun everyRegexLiteralIsPortable() {
        val files = listOf("app/src/main", "shared/src/main", "desktop/src/main").map { File(root, it) }.filter { it.exists() }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("no sources found under $root", files.isNotEmpty())
        var count = 0
        val bad = ArrayList<String>()
        for (f in files) {
            val text = f.readText()
            val pats = raw.findAll(text).map { it.groupValues[1] } + plain.findAll(text).map { unescapeKotlin(it.groupValues[1]) }
            for (p in pats) {
                count++
                try { Regex(p) } catch (e: Exception) { bad += "${f.name}: $p does not compile (${e.message})" }
                icuProblems(p).forEach { bad += "${f.name}: $p $it" }
            }
        }
        assertTrue("expected to find regex literals", count > 5)
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun catchupPlaceholders() {
        val ch = Channel("k", "s", "n", null, "g", "http://h/live/1.ts", null, 1, Catchup("default", "http://h/a?s={utc}&e=\${end}&d={duration}&x={other}"), 3)
        assertEquals("http://h/a?s=1000&e=4600&d=3600&x={other}", CatchupUrl.build(ch, 1000, 4600, 5000))
    }
}
