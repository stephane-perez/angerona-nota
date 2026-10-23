package com.stephaneperez.notepad.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pure (Android-free) part of opening a document: bounded reading and deciding whether
 * the bytes are one of our encrypted containers or a legacy plain-text file. Kept out of
 * [NotepadViewModel] so it can be unit-tested on the JVM.
 */
internal object DocumentCodec {

    /** Outcome of [decode]; the ViewModel maps each failure to its toast. */
    sealed interface Result {
        data class Ok(val text: String) : Result
        data object TooLarge : Result
        data object DecryptFailed : Result
    }

    /** Thrown by [readBytesUpTo] to distinguish "too large" from other read failures. */
    class TooLargeException : Exception()

    /**
     * Reads [stream] up to [maxBytes], throwing [TooLargeException] as soon as more than
     * that has been read — never buffering an oversized file fully in memory just to
     * measure it.
     */
    fun readBytesUpTo(stream: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read == -1) break
            total += read
            if (total > maxBytes) throw TooLargeException()
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    /**
     * Classifies and decodes [bytes] read with the [CryptoManager.MAX_CONTAINER_BYTES]
     * bound. [decrypt] is [CryptoManager.decrypt] in production, injectable for tests.
     */
    fun decode(bytes: ByteArray, decrypt: (ByteArray) -> String? = CryptoManager::decrypt): Result {
        if (CryptoManager.looksEncrypted(bytes)) {
            // This IS one of our files (magic header matches). If it can't be decrypted
            // — wrong/missing key or tampering — showing the raw bytes as "text" would
            // just be noise; report a failure instead.
            return decrypt(bytes)?.let { Result.Ok(it) } ?: Result.DecryptFailed
        }
        // Doesn't look like one of ours at all — an ordinary legacy .txt file. Checked
        // against MAX_PLAINTEXT_BYTES here (tighter than the MAX_CONTAINER_BYTES bound
        // used for the initial bounded read): the 100 KB limit applies uniformly to any
        // note this app holds, not just to our own encrypted ones.
        if (bytes.size > CryptoManager.MAX_PLAINTEXT_BYTES) return Result.TooLarge
        // Deliberately lenient UTF-8 decoding here (unlike the strict decoder in
        // CryptoManager.decrypt()): this path handles arbitrary external files we don't
        // control, which may be in any encoding — rejecting them outright would break
        // opening perfectly legitimate legacy notes.
        return Result.Ok(String(bytes, Charsets.UTF_8))
    }
}
