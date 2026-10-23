package com.stephaneperez.notepad.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Exercises the on-disk container format with a software AES-256 key: the Android
 * Keystore only exists on a device, but everything else in [CryptoManager] is plain JCA.
 */
class CryptoManagerTest {

    private val key: SecretKey = newKey()
    private val header = byteArrayOf('S'.code.toByte(), 'N'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 1)

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun encrypt(text: String) = CryptoManager.encryptWith(text) { key }
    private fun decrypt(data: ByteArray, k: SecretKey? = key) = CryptoManager.decryptWith(data) { k }

    @Test
    fun roundTripPreservesText() {
        for (text in listOf("", "hello", "accents éèàç — emoji 🔐\nline 2\r\nline 3\t", "\u0000x")) {
            assertEquals(text, decrypt(encrypt(text)))
        }
    }

    @Test
    fun containerLayoutIsHeaderIvCiphertextTag() {
        val text = "some note"
        val data = encrypt(text)
        assertArrayEquals(header, data.copyOfRange(0, 5))
        assertEquals(5 + 12 + text.toByteArray().size + 16, data.size)
        assertTrue(CryptoManager.looksEncrypted(data))
    }

    @Test
    fun containerBoundIsPlaintextLimitPlusOverhead() {
        assertEquals(100 * 1024, CryptoManager.MAX_PLAINTEXT_BYTES)
        assertEquals(CryptoManager.MAX_PLAINTEXT_BYTES + 5 + 12 + 16, CryptoManager.MAX_CONTAINER_BYTES)
    }

    @Test
    fun sameTextEncryptsDifferentlyEachTime() {
        assertNotEquals(encrypt("same").toList(), encrypt("same").toList())
    }

    @Test
    fun maximalNoteRoundTripsAndFitsContainerBound() {
        val text = "a".repeat(CryptoManager.MAX_PLAINTEXT_BYTES)
        val data = encrypt(text)
        assertEquals(CryptoManager.MAX_CONTAINER_BYTES, data.size)
        assertEquals(text, decrypt(data))
    }

    @Test
    fun oversizedTextIsRefusedBeforeTouchingTheKey() {
        // 'é' is 2 bytes in UTF-8: the limit is on encoded bytes, not on chars.
        val text = "é".repeat(CryptoManager.MAX_PLAINTEXT_BYTES / 2 + 1)
        try {
            CryptoManager.encryptWith(text) { fail("key must not be requested"); key }
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun anyTamperedByteFailsDecryption() {
        val data = encrypt("do not touch")
        // Every byte after the header (IV, ciphertext, tag); header bytes are covered below.
        for (i in 5 until data.size) {
            val tampered = data.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull("byte $i", decrypt(tampered))
        }
    }

    @Test
    fun alteredHeaderIsNotRecognisedAsOurs() {
        val data = encrypt("x")
        val wrongVersion = data.copyOf().also { it[4] = 2 }
        val wrongMagic = data.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFalse(CryptoManager.looksEncrypted(wrongVersion))
        assertFalse(CryptoManager.looksEncrypted(wrongMagic))
        assertNull(decrypt(wrongVersion))
    }

    @Test
    fun truncatedContainerFailsDecryption() {
        val data = encrypt("truncate me")
        assertNull(decrypt(data.copyOf(data.size - 1)))
    }

    @Test
    fun wrongOrMissingKeyFailsWithoutThrowing() {
        val data = encrypt("secret")
        assertNull(decrypt(data, newKey()))
        assertNull(decrypt(data, null))
        assertNull(CryptoManager.decryptWith(data) { throw IllegalStateException("keystore unavailable") })
    }

    @Test
    fun oversizedContainerIsRejectedBeforeTouchingTheKey() {
        val data = header + ByteArray(CryptoManager.MAX_CONTAINER_BYTES)
        assertNull(CryptoManager.decryptWith(data) { fail("key must not be requested"); key })
    }

    @Test
    fun authenticButInvalidUtf8IsRejected() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(header)
        val iv = cipher.iv
        val data = header + iv + cipher.doFinal(byteArrayOf(0xC3.toByte(), 0x28))
        assertNull(decrypt(data))

        // Sanity check: the same construction with valid UTF-8 does decrypt.
        val ok = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(header)
        }
        assertEquals("ok", decrypt(header + ok.iv + ok.doFinal("ok".toByteArray())))
    }

    @Test
    fun looksEncryptedRejectsPlainTextAndShortInput() {
        assertFalse(CryptoManager.looksEncrypted("just a plain note".toByteArray()))
        assertFalse(CryptoManager.looksEncrypted(ByteArray(0)))
        // Right header, but shorter than header + IV + tag: can't be a real container.
        assertFalse(CryptoManager.looksEncrypted(header + ByteArray(12 + 15)))
        assertTrue(CryptoManager.looksEncrypted(header + ByteArray(12 + 16)))
    }
}
