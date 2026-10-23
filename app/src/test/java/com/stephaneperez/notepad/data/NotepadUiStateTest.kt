package com.stephaneperez.notepad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotepadUiStateTest {

    @Test
    fun defaultsMatchAFreshUntitledBuffer() {
        val state = NotepadUiState()
        assertEquals("untitled.txt", state.filename)
        assertNull(state.uri)
        assertFalse(state.dirty)
        assertTrue(state.wrap)
        assertFalse(state.gutterVisible)
        assertNull(state.confirm)
    }

    @Test
    fun lineCount() {
        assertEquals(1, NotepadUiState(text = "").lineCount)
        assertEquals(1, NotepadUiState(text = "one").lineCount)
        assertEquals(2, NotepadUiState(text = "one\n").lineCount)
        assertEquals(3, NotepadUiState(text = "a\nb\nc").lineCount)
    }

    @Test
    fun charCount() {
        assertEquals(0, NotepadUiState(text = "").charCount)
        assertEquals(4, NotepadUiState(text = "ab\nc").charCount)
    }

    @Test
    fun gutterOnlyVisibleWithLineNumbersAndNoWrap() {
        assertTrue(NotepadUiState(wrap = false, lineNumbers = true).gutterVisible)
        assertFalse(NotepadUiState(wrap = true, lineNumbers = true).gutterVisible)
        assertFalse(NotepadUiState(wrap = false, lineNumbers = false).gutterVisible)
    }

    @Test
    fun totalMatchesIsLiteralCaseSensitiveAndNonOverlapping() {
        assertEquals(0, NotepadUiState(text = "abc", query = "").totalMatches)
        assertEquals(0, NotepadUiState(text = "abc", query = "x").totalMatches)
        assertEquals(1, NotepadUiState(text = "Cat cat", query = "cat").totalMatches)
        assertEquals(2, NotepadUiState(text = "aaaa", query = "aa").totalMatches)
        assertEquals(1, NotepadUiState(text = "aaa", query = "aa").totalMatches)
        assertEquals(1, NotepadUiState(text = "a.c abc", query = ".").totalMatches)
    }

    @Test
    fun totalMatchesAgreesWithReplaceAll() {
        // replaceAll() reports totalMatches as the replaced count, then uses String.replace.
        for ((text, query) in listOf("aaaa" to "aa", "aaa" to "aa", "x-x-x" to "-", "abab" to "aba")) {
            val state = NotepadUiState(text = text, query = query)
            val replaced = text.replace(query, "\u0000")
            assertEquals("$text / $query", state.totalMatches, replaced.count { it == '\u0000' })
        }
    }
}
