// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * User organisation of channels and groups, plus parental lock. Keys are channel keys / original group names.
 * [locked] holds live group names and film / show category names (as on Android); [lockedCh] single channels.
 */
@Serializable
data class Prefs(
    val hiddenCh: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
    val order: List<String> = emptyList(),
    val hiddenGroups: Set<String> = emptySet(),
    val groupNames: Map<String, String> = emptyMap(),
    val groupOrder: List<String> = emptyList(),
    val custom: Map<String, List<String>> = emptyMap(),
    val locked: Set<String> = emptySet(),
    val pinHash: String = "",
    val lockedCh: Set<String> = emptySet(),
)

/** Settings and organisation, for backup. Source credentials are deliberately not included. */
@Serializable
data class Backup(
    val version: Int = 1,
    val epgHours: Int = 24, val epgShiftMin: Int = 0, val startLast: Boolean = true, val bufferSec: Int = 15,
    val recPadStart: Int = 1, val recPadEnd: Int = 3,
    val favourites: Set<String> = emptySet(),
    val epgMap: Map<String, String> = emptyMap(),
    val prefs: Prefs = Prefs(),
)

/**
 * The parental PIN is stored only as a salted PBKDF2-HMAC-SHA256 hash ("pbkdf2_sha256$iterations$salt$hash", the
 * web app's format). Hashes of the first versions (unsalted SHA-256) are still accepted, so an old PIN keeps working;
 * the next PIN change stores the new form. PBKDF2 is computed on HMAC-SHA256 directly (every Android version has it).
 */
object Pin {
    const val ITERATIONS = 120_000

    fun hash(pin: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }, iterations: Int = ITERATIONS): String =
        "pbkdf2_sha256$$iterations$${hex(salt)}$${hex(pbkdf2(pin.toByteArray(), salt, iterations, 32))}"

    fun verify(pin: String, stored: String): Boolean {
        if (stored.isEmpty() || pin.isEmpty()) return false
        val parts = stored.split('$')
        if (parts.size != 4) return MessageDigest.isEqual(stored.toByteArray(), legacy(pin).toByteArray())
        if (parts[0] != "pbkdf2_sha256") return false
        val it = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val salt = unhex(parts[2]) ?: return false
        val want = unhex(parts[3]) ?: return false
        return MessageDigest.isEqual(pbkdf2(pin.toByteArray(), salt, it, want.size), want)
    }

    fun check(p: Prefs, pin: String) = p.pinHash.isNotEmpty() && verify(pin, p.pinHash)

    /** A PIN is 4 to 8 digits. */
    fun validFormat(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

    /** The unsalted hash of the first versions (read only). */
    fun legacy(pin: String): String = hex(MessageDigest.getInstance("SHA-256").digest(("tvbro:$pin").toByteArray()))

    /** RFC 8018 PBKDF2 with HMAC-SHA256. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        require(password.isNotEmpty()) { "empty password" }
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(len)
        var block = 1; var pos = 0
        while (pos < len) {
            mac.update(salt); mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal(); val t = u.copyOf()
            for (i in 1 until iterations) { u = mac.doFinal(u); for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte() }
            val n = minOf(t.size, len - pos); System.arraycopy(t, 0, out, pos, n); pos += n; block++
        }
        return out
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray? = if (s.length % 2 != 0 || s.isEmpty()) null else try {
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    } catch (e: NumberFormatException) { null }
}

/**
 * Asking for the PIN: after 5 wrong PINs in a row every further try waits 30 s (60 s after 10, up to 8 min), so a
 * 4-digit PIN cannot be walked through. A right PIN unlocks everything for [UNLOCK_MS] (4 h); [lockNow] ends it.
 * In memory only: a restart locks again. Times are milliseconds from [clock].
 */
class PinGate(private val clock: () -> Long = System::currentTimeMillis) {
    sealed class Result { object Ok : Result(); data class Wrong(val waitSec: Int) : Result(); data class Wait(val sec: Int) : Result() }

    private var fails = 0
    private var waitUntil = 0L
    var unlockedUntil = 0L
        private set

    val unlocked: Boolean get() = clock() < unlockedUntil
    /** Seconds still to wait before the next try (0: may try). */
    fun waitSec(): Int { val d = waitUntil - clock(); return if (d <= 0) 0 else ((d + 999) / 1000).toInt() }

    fun check(stored: String, pin: String): Result {
        val now = clock()
        if (now < waitUntil) return Result.Wait(waitSec())
        if (stored.isNotEmpty() && Pin.verify(pin, stored)) { fails = 0; unlockedUntil = now + UNLOCK_MS; return Result.Ok }
        fails++
        if (fails >= MAX_FAILS) waitUntil = now + 30_000L * (1L shl ((fails - MAX_FAILS) / MAX_FAILS).coerceAtMost(4))
        return Result.Wrong(waitSec())
    }

    fun lockNow() { unlockedUntil = 0 }

    companion object {
        const val MAX_FAILS = 5
        const val UNLOCK_MS = 4 * 3600_000L
    }
}

object BackupIo {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    fun encode(b: Backup): String = json.encodeToString(b)
    fun decode(s: String): Backup = json.decodeFromString(s)
}
