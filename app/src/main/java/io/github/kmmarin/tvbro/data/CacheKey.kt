// Copyright 2026 The tvBro authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The 32-byte AES key for the sealed cache files ([Sealed]). It is random, and stored only wrapped (AES-256-GCM) by a
 * non-exportable Android Keystore key, in filesDir/cache.key ("TVK1" | IV (12) | ciphertext + tag). It is unwrapped
 * once per process (one Keystore operation), then the file work runs in software AES-GCM.
 * When the Keystore key is gone (data moved to another device, Keystore reset) a new key is made: the old cache files
 * then read as missing and are downloaded again.
 */
object CacheKey {
    private const val ALIAS = "tvbro_cache_wrap"
    private val MAGIC = "TVK1".toByteArray()
    @Volatile private var key: ByteArray? = null

    @Synchronized fun get(ctx: Context): ByteArray = key ?: load(ctx.applicationContext).also { key = it }

    private fun file(ctx: Context) = File(ctx.filesDir, "cache.key")

    private fun load(ctx: Context): ByteArray {
        val f = file(ctx)
        if (f.exists()) {
            // a Keystore that is only busy just after boot gets a second try before the cache is given up
            for (attempt in 0..1) {
                try { return unwrapOnce(f) } catch (e: Exception) {
                    if (attempt == 0) Thread.sleep(400) else Diag.log("cache key unreadable (${e.javaClass.simpleName}), making a new one")
                }
            }
        }
        return create(ctx)
    }

    private fun unwrapOnce(f: File): ByteArray {
        val b = f.readBytes()
        require(b.size > 4 + 12 + 16 && b.copyOf(4).contentEquals(MAGIC)) { "bad key file" }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrap = ks.getKey(ALIAS, null) as? SecretKey ?: throw IllegalStateException("no Keystore key")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrap, GCMParameterSpec(128, b.copyOfRange(4, 16)))
        return c.doFinal(b, 16, b.size - 16).also { require(it.size == 32) }
    }

    private fun create(ctx: Context): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        runCatching { ks.deleteEntry(ALIAS) }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        val wrap = gen.generateKey()
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrap) // the Keystore picks the IV
        val ct = c.doFinal(k)
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        tmp.writeBytes(MAGIC + c.iv + ct)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        Diag.log("cache key created")
        return k
    }
}
