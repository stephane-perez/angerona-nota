package com.stephaneperez.notepad.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and decrypts note content with an AES-256-GCM key held in the Android
 * Keystore.
 *
 * The key is generated once (lazily, on first save) and is non-exportable and scoped to
 * this app's UID: another app cannot access it, and this file cannot be decrypted on
 * another device without the corresponding key. Whether the key is additionally backed
 * by dedicated secure hardware (TEE/StrongBox) or a software Keystore implementation
 * depends on the device; the Keystore API guarantees non-exportability either way, but
 * not hardware backing specifically. The key is not recoverable across an app uninstall
 * or a factory reset: a fresh install always gets a fresh key. Files encrypted under a
 * since-lost key can no longer be decrypted — see [decrypt] / [looksEncrypted] and the
 * README for how the app surfaces that.
 */
object CryptoManager {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "simple_notepad_file_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
    private const val IV_LENGTH_BYTES = 12

    /** Files above this size are refused on open and on save — see README, "File encryption". */
    const val MAX_PLAINTEXT_BYTES = 100 * 1024 // 100 KB

    // On-disk container: "SNPD" magic + 1-byte format version + 12-byte GCM IV +
    // ciphertext (the 16-byte GCM auth tag is appended to the ciphertext by the cipher).
    // MAGIC + version are passed to GCM as AAD, so tampering with the header itself
    // (not just the ciphertext) is also caught by authentication.
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'N'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte())
    private const val FORMAT_VERSION: Byte = 1
    private const val HEADER_LENGTH = 4 + 1 // MAGIC + version
    private val HEADER = MAGIC + byteArrayOf(FORMAT_VERSION)

    /**
     * Upper bound on the whole on-disk container (header + IV + plaintext + GCM tag).
     * Enforced by [decrypt] itself before any allocation — not just by callers — so a
     * huge/malicious file can't force a large `copyOfRange`/`doFinal` allocation just by
     * being handed to this class directly. Public so callers reading raw, not-yet
     * classified bytes (before knowing if they're one of ours or legacy plain text) can
     * check against the correct, larger bound rather than the plaintext-only one.
     */
    const val MAX_CONTAINER_BYTES = HEADER_LENGTH + IV_LENGTH_BYTES + MAX_PLAINTEXT_BYTES + GCM_TAG_LENGTH_BYTES

    /** Smallest a real container could ever be: header + IV + GCM tag, with an empty ciphertext. */
    private const val MIN_CONTAINER_BYTES = HEADER_LENGTH + IV_LENGTH_BYTES + GCM_TAG_LENGTH_BYTES

    private val keyLock = Any()

    /** Only used by [encrypt] — decryption must never have the side effect of minting a key. */
    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        getExistingKeyLocked() ?: run {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No biometric/lock-screen prompt on every save/open — encryption here
                // is meant to keep files opaque to *other apps and other devices*, not
                // to gate access behind an unlock inside the app itself.
                .setUserAuthenticationRequired(false)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getExistingKey(): SecretKey? = synchronized(keyLock) { getExistingKeyLocked() }

    /** Assumes [keyLock] is already held — never call directly, only from a synchronized block. */
    private fun getExistingKeyLocked(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    /**
     * Encrypts [plainText] (UTF-8) into the on-disk container format described above.
     * Throws [IllegalArgumentException] if the encoded text exceeds [MAX_PLAINTEXT_BYTES]
     * — callers should check size before calling this, this is a last-resort guard.
     */
    fun encrypt(plainText: String): ByteArray = encryptWith(plainText) { getOrCreateKey() }

    /**
     * Container logic behind [encrypt], with the key supplied by [keyProvider] (only
     * invoked once the size check has passed). Internal so JVM unit tests can exercise
     * the format with a software key — the Android Keystore isn't available off-device.
     */
    internal fun encryptWith(plainText: String, keyProvider: () -> SecretKey): ByteArray {
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)
        require(plainBytes.size <= MAX_PLAINTEXT_BYTES) { "Text exceeds MAX_PLAINTEXT_BYTES" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        cipher.updateAAD(HEADER)
        val iv = cipher.iv // Keystore-generated random IV
        require(iv.size == IV_LENGTH_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        val ciphertext = cipher.doFinal(plainBytes)
        return HEADER + iv + ciphertext
    }

    /**
     * True if [data] starts with our magic header and format version — i.e. this is (or
     * was) one of our encrypted files, regardless of whether it can still be decrypted.
     * Used to distinguish "not ours, treat as plain text" from "ours, but undecryptable"
     * — see the README, "File encryption".
     */
    fun looksEncrypted(data: ByteArray): Boolean {
        if (data.size < MIN_CONTAINER_BYTES) return false
        for (i in MAGIC.indices) if (data[i] != MAGIC[i]) return false
        return data[MAGIC.size] == FORMAT_VERSION
    }

    /**
     * Attempts to decrypt [data] written by [encrypt]. Returns null if [data] doesn't
     * look like one of our files at all, is larger than this class can ever have
     * produced, or if decryption fails for any reason (key lost to a reinstall/factory
     * reset, corrupted data, tampering — GCM authentication covers both the ciphertext
     * and the header via AAD, and the decrypted bytes are required to be valid UTF-8).
     * Only ever reads an existing Keystore key — never creates one as a side effect of a
     * failed open.
     */
    fun decrypt(data: ByteArray): String? = decryptWith(data) { getExistingKey() }

    /** Logic behind [decrypt], with the key supplied by [keyProvider] — see [encryptWith]. */
    internal fun decryptWith(data: ByteArray, keyProvider: () -> SecretKey?): String? {
        if (!looksEncrypted(data)) return null
        // Enforced here, not just by callers: refuse an oversized container before any
        // copyOfRange/doFinal allocation, so this class can't be handed a huge buffer
        // and OOM regardless of what the caller already checked.
        if (data.size > MAX_CONTAINER_BYTES) return null

        // The whole body — including Keystore access, which can itself throw (corrupted
        // keystore, provider unavailable) — is inside one runCatching, so this function
        // truly never throws and always returns null on any failure, as documented.
        return runCatching {
            val key = keyProvider() ?: return@runCatching null

            val iv = data.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + IV_LENGTH_BYTES)
            val ciphertext = data.copyOfRange(HEADER_LENGTH + IV_LENGTH_BYTES, data.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            cipher.updateAAD(HEADER)
            val plainBytes = cipher.doFinal(ciphertext)
            // Strict UTF-8 decoding: String(bytes, UTF_8) silently replaces invalid
            // sequences with U+FFFD instead of failing, which could mask corruption as
            // ordinary-looking (if garbled) text. Since this class only ever encrypts
            // valid UTF-8 (see encrypt()), a successful GCM auth check should always
            // yield valid UTF-8 too; treat anything else as a decryption failure.
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plainBytes))
                .toString()
        }.getOrNull()
    }
}
