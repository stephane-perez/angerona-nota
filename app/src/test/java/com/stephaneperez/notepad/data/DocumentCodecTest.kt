package com.stephaneperez.notepad.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.crypto.KeyGenerator

class DocumentCodecTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val decryptWithTestKey: (ByteArray) -> String? = { CryptoManager.decryptWith(it) { key } }

    private fun read(size: Int, max: Int) = DocumentCodec.readBytesUpTo(ByteArrayInputStream(ByteArray(size) { it.toByte() }), max)

    @Test
    fun readsWholeStreamUpToTheLimit() {
        assertEquals(0, read(0, 10).size)
        assertArrayEquals(ByteArray(20_000) { it.toByte() }, read(20_000, 20_000))
    }

    @Test
    fun throwsAsSoonAsTheLimitIsExceeded() {
        try {
            read(20_001, 20_000)
            fail("expected TooLargeException")
        } catch (expected: DocumentCodec.TooLargeException) {
        }
    }

    @Test
    fun stopsReadingOversizedStreamEarly() {
        // An endless stream must not be buffered fully just to measure it.
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        try {
            DocumentCodec.readBytesUpTo(endless, CryptoManager.MAX_CONTAINER_BYTES)
            fail("expected TooLargeException")
        } catch (expected: DocumentCodec.TooLargeException) {
        }
        assertTrue(served <= CryptoManager.MAX_CONTAINER_BYTES + 8192)
    }

    @Test
    fun legacyPlainTextOpensAsIs() {
        val result = DocumentCodec.decode("plain note\n".toByteArray(), decryptWithTestKey)
        assertEquals(DocumentCodec.Result.Ok("plain note\n"), result)
    }

    @Test
    fun legacyPlainTextIsDecodedLeniently() {
        // Latin-1 'é' is invalid UTF-8: still opens (with a replacement char), never fails.
        val result = DocumentCodec.decode(byteArrayOf('a'.code.toByte(), 0xE9.toByte()), decryptWithTestKey)
        assertEquals(DocumentCodec.Result.Ok("a�"), result)
    }

    @Test
    fun legacyPlainTextHonoursThePlaintextLimit() {
        val atLimit = ByteArray(CryptoManager.MAX_PLAINTEXT_BYTES) { 'a'.code.toByte() }
        assertTrue(DocumentCodec.decode(atLimit, decryptWithTestKey) is DocumentCodec.Result.Ok)
        val overLimit = ByteArray(CryptoManager.MAX_PLAINTEXT_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(DocumentCodec.Result.TooLarge, DocumentCodec.decode(overLimit, decryptWithTestKey))
    }

    @Test
    fun ownEncryptedFileOpens() {
        val data = CryptoManager.encryptWith("secret note") { key }
        assertEquals(DocumentCodec.Result.Ok("secret note"), DocumentCodec.decode(data, decryptWithTestKey))
    }

    @Test
    fun maximalEncryptedNoteSurvivesTheBoundedReadAndOpens() {
        // Regression (DEVELOPMENT.md, round 4): a full 100 KB note's container is larger
        // than MAX_PLAINTEXT_BYTES and must still reopen.
        val text = "z".repeat(CryptoManager.MAX_PLAINTEXT_BYTES)
        val data = CryptoManager.encryptWith(text) { key }
        val bytes = DocumentCodec.readBytesUpTo(ByteArrayInputStream(data), CryptoManager.MAX_CONTAINER_BYTES)
        assertEquals(DocumentCodec.Result.Ok(text), DocumentCodec.decode(bytes, decryptWithTestKey))
    }

    @Test
    fun undecryptableOwnFileIsAnErrorNeverRawText() {
        val data = CryptoManager.encryptWith("secret") { key }
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val result = DocumentCodec.decode(data) { CryptoManager.decryptWith(it) { otherKey } }
        assertEquals(DocumentCodec.Result.DecryptFailed, result)

        val tampered = data.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(DocumentCodec.Result.DecryptFailed, DocumentCodec.decode(tampered, decryptWithTestKey))
    }
}
