// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import io.github.kmmarin.tvbro.data.ChannelView
import io.github.kmmarin.tvbro.data.Diag
import io.github.kmmarin.tvbro.data.Prefs
import io.github.kmmarin.tvbro.data.Source
import io.github.kmmarin.tvbro.data.TimeFmt
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Paths {
    private val home = System.getProperty("user.home")
    /** Overridable for tests (-Dtvbro.config=<dir>). */
    val config: File = File(System.getProperty("tvbro.config") ?: File(
        if (Platform.windows) System.getenv("APPDATA") ?: "$home\\AppData\\Roaming"
        else System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", if (Platform.windows) "tvBro" else "tvbro"
    ).path).let { Perms.dir(it) }.also { Perms.tighten(it) } // 0700 / 0600 on Linux, also for files of older versions
    val cache: File = File(System.getProperty("tvbro.cache") ?: File(
        if (Platform.windows) System.getenv("LOCALAPPDATA") ?: "$home\\AppData\\Local"
        else System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", if (Platform.windows) "tvBro\\cache" else "tvbro"
    ).path).let { Perms.dir(it) }
    val recordings: File = File(home, if (Platform.windows) "Videos\\tvBro" else "Videos/tvBro")
    /** Network-share recordings are written here first and copied to the share when they finish. */
    val recStaging: File get() = Perms.dir(File(cache, "rec-staging"))
}

/** Network share for recordings. The password is not here: it is kept encrypted in [Secrets] ("smb"). */
@Serializable
data class SmbCfg(val host: String = "", val share: String = "", val path: String = "tvBro", val user: String = "", val domain: String = "") {
    val ok: Boolean get() = host.isNotBlank() && share.isNotBlank()
    /** \\host\share\path, without the user name or password. */
    val label: String get() = "\\\\$host\\$share" + (if (path.isNotBlank()) "\\" + path.trim('/', '\\').replace('/', '\\') else "")
    /** smb://host/share/path/ (no credentials in the address). */
    val dirUrl: String get() = "smb://${host.trim()}/${share.trim('/', '\\')}/" + path.trim('/', '\\').replace('\\', '/').let { if (it.isEmpty()) "" else "$it/" }
}

/** Programme reminder: pops up a minute before [startMs]. */
@Serializable
data class Reminder(val id: String, val chKey: String, val channel: String, val title: String, val startMs: Long)

@Serializable
data class Settings(
    val epgHours: Int = 12,
    val catalogueHours: Int = 24,
    val hwdec: String = if (Platform.windows) "auto-safe" else "v4l2m2m-copy,auto-safe",
    val lastChannel: String = "",
    val myList: List<String> = emptyList(), // channel keys, VOD item keys and "g|<group>"
    val resume: Map<String, Long> = emptyMap(), // legacy (seconds); moved into watch.json on first start
    val recDir: String = "",
    val audioDelayMs: Int = 0,
    // added 2026-11-11 (Android parity)
    val timeFmt: Int = TimeFmt.H12,
    val autoplayNext: Boolean = true,
    val prefs: Prefs = Prefs(),
    val numberMode: Int = ChannelView.NUM_PROVIDER,
    val lastGroup: String = ChannelView.ALL,
    val lastUi: String = "",
    val startLast: Boolean = true,
    val epgShiftMin: Int = 0,
    val previewOn: Boolean = true,
    val previewMuted: Boolean = false,
    /** Recording storage: "local" (folder), "drive" (USB / removable drive) or "smb" (network share). */
    val storage: String = "local",
    val drivePath: String = "",
    val smb: SmbCfg = SmbCfg(),
    val recPadStartMin: Int = 1,
    val recPadEndMin: Int = 3,
    val searchHistory: List<String> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val captionSize: Int = 1,
    val captionBg: Int = 0,
    /** Manual guide mapping: channel key -> XMLTV channel id. */
    val epgMap: Map<String, String> = emptyMap(),
    /** External player command (e.g. vlc); [useExternal] plays everything full screen with it. */
    val externalPlayer: String = "",
    val useExternal: Boolean = false,
)

object Json2 { val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true } }

object SettingsStore {
    private val f = File(Paths.config, "settings.json")
    fun load(): Settings = try { Json2.json.decodeFromString(Settings.serializer(), f.readText()) } catch (e: Exception) { Settings() }
    fun save(s: Settings) { try { atomicWrite(f, Json2.json.encodeToString(Settings.serializer(), s).toByteArray()) } catch (e: Exception) { Diag.log("settings save failed") } }
}

/**
 * Encrypted named files in [dir] ("<name>.bin") with a pluggable [encrypt] / [decrypt]. A file that exists but can no
 * longer be decrypted is never overwritten silently: it is kept as "<name>.bin.bak" (or a dated .bak) first.
 */
class SecretBox(private val dir: File, private val encrypt: (ByteArray) -> ByteArray, private val decrypt: (ByteArray) -> ByteArray) {
    fun file(name: String) = File(dir, "$name.bin")
    fun read(name: String): ByteArray? = file(name).takeIf { it.exists() }?.let { decrypt(it.readBytes()) }

    /** Returns the backup made of an unreadable old file, or null. */
    fun write(name: String, b: ByteArray): File? {
        val sealed = encrypt(b) // fails (and changes nothing) while the key is unavailable
        val f = file(name)
        val bak = if (f.exists() && !readable(f)) keep(f) else null
        atomicWrite(f, sealed)
        return bak
    }

    private fun readable(f: File) = try { decrypt(f.readBytes()); true } catch (e: Exception) { false }

    private fun keep(f: File): File {
        var b = File(dir, f.name + ".bak")
        if (b.exists()) b = File(dir, f.name + "." + java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date()) + ".bak")
        Perms.replace(f, b)
        Diag.log("${f.name} could not be decrypted: kept as ${b.name}")
        return b
    }
}

/**
 * Secrets (source credentials, the share password) are stored encrypted: Windows DPAPI (current user); on Linux the
 * desktop keyring through `secret-tool` (libsecret) when present, else AES-GCM with a random key in the config dir
 * (mode 600). A keyring that is locked or not reachable yet (autostart) is an error, never a reason for a new key.
 */
object Secrets {
    private val box = SecretBox(Paths.config, ::encrypt, ::decrypt)
    fun file(name: String) = box.file(name)
    fun read(name: String): ByteArray? = box.read(name)
    fun write(name: String, b: ByteArray): File? = box.write(name, b)
    fun readString(name: String): String = try { read(name)?.let { String(it, Charsets.UTF_8) } ?: "" } catch (e: Exception) { Diag.log("$name unreadable (${e.javaClass.simpleName})"); "" }
    fun writeString(name: String, s: String) = write(name, s.toByteArray(Charsets.UTF_8))

    val method: String get() = when { Platform.windows -> "Windows DPAPI"; secretTool -> "libsecret keyring"; else -> "AES-GCM, key file (600)" }

    private val secretTool: Boolean by lazy {
        !Platform.windows && System.getenv("PATH").orEmpty().split(':').any { File(it, "secret-tool").canExecute() } && System.getenv("DBUS_SESSION_BUS_ADDRESS") != null
    }

    private fun encrypt(b: ByteArray): ByteArray = when {
        Platform.windows -> byteArrayOf(1) + Crypt32Util.cryptProtectData(b)
        else -> { val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }; byteArrayOf(2) + iv + cipher(Cipher.ENCRYPT_MODE, iv).doFinal(b) }
    }

    private fun decrypt(b: ByteArray): ByteArray = when (b.firstOrNull()?.toInt()) {
        1 -> Crypt32Util.cryptUnprotectData(b.copyOfRange(1, b.size))
        2 -> cipher(Cipher.DECRYPT_MODE, b.copyOfRange(1, 13)).doFinal(b, 13, b.size - 13)
        else -> error("unknown format")
    }

    private fun cipher(mode: Int, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, iv))
    }

    // a failed load is not cached: the next use tries again (the keyring may be unlocked by then)
    private val keyBytes: ByteArray by lazy { loadKey() }
    private fun key() = keyBytes

    private fun loadKey(): ByteArray {
        val kf = File(Paths.config, "key.bin")
        if (secretTool) {
            val (code, out, err) = run("secret-tool", "lookup", "app", "tvbro")
            when (Keyring.classify(code, out, err)) {
                KeyringLookup.FOUND -> return Keyring.decode(out)!!
                KeyringLookup.ERROR -> throw java.io.IOException("keyring not available")
                KeyringLookup.NOT_FOUND -> {
                    if (kf.exists()) return KeyFile.loadOrCreate(kf) // secrets written while no keyring was running
                    val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    val p = ProcessBuilder("secret-tool", "store", "--label=tvBro", "app", "tvbro").start()
                    p.outputStream.use { it.write(Base64.getEncoder().encode(k)) }
                    if (p.waitFor() == 0 && Keyring.decode(run("secret-tool", "lookup", "app", "tvbro").second)?.contentEquals(k) == true) return k
                    Diag.log("keyring store failed: using the key file")
                }
            }
        }
        return KeyFile.loadOrCreate(kf)
    }

    private fun run(vararg cmd: String): Triple<Int, String, String> = try {
        val p = ProcessBuilder(*cmd).start()
        val err = StringBuilder()
        val t = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { start() }
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor(); t.join(2000)
        Triple(code, out, err.toString())
    } catch (e: Exception) { Triple(-1, "", e.javaClass.simpleName) }
}

/** Playlist sources with their credentials, encrypted through [Secrets] ("sources.bin"). */
object SourceStore {
    private val ser = ListSerializer(Source.serializer())
    /** Why the saved playlists could not be read at start (shown once), or null. */
    @Volatile var problem: String? = null
        private set
    fun load(): List<Source> = try {
        Secrets.read("sources")?.let { Json2.json.decodeFromString(ser, String(it, Charsets.UTF_8)) } ?: emptyList()
    } catch (e: Exception) {
        Diag.log("sources unreadable (${e.javaClass.simpleName})")
        problem = "The saved playlists could not be decrypted (${if (e is java.io.IOException) e.message else "another key"}). They are kept: saving a playlist moves the old file to sources.bin.bak."
        emptyList()
    }
    /** Saves; null = saved, else a message for the user. */
    fun save(list: List<Source>): String? = try {
        Secrets.write("sources", Json2.json.encodeToString(ser, list).toByteArray())?.let { "The old, unreadable playlists file was kept as ${it.name}" }
    } catch (e: Exception) { Diag.log("sources save failed (${e.javaClass.simpleName})"); "Playlists not saved: ${e.message ?: "the key is not available"}" }
    val method: String get() = Secrets.method
}
