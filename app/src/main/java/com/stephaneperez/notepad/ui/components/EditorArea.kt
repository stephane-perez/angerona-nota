package com.stephaneperez.notepad.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stephaneperez.notepad.ui.theme.NotepadColors
import com.stephaneperez.notepad.ui.theme.NotepadType

/**
 * Wrap off: no soft wrap, horizontal scrolling; the gutter stays fixed.
 * Wrap on (default): soft wrap, no horizontal scroll; the gutter is hidden by the caller.
 * The gutter and the text share one vertical ScrollState so line numbers track the text.
 */
@Composable
fun EditorArea(
    text: String,
    onTextChanged: (String) -> Unit,
    wrap: Boolean,
    gutterVisible: Boolean,
    lineCount: Int,
    modifier: Modifier = Modifier,
) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(NotepadColors.ground)
    ) {
        if (gutterVisible) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(NotepadColors.neutral100)
                    .trailingHairline(NotepadColors.divider)
                    .verticalScroll(verticalScroll)
                    .padding(vertical = 14.dp, horizontal = 9.dp)
            ) {
                Text(
                    text = (1..lineCount).joinToString("\n") { it.toString() },
                    style = NotepadType.gutter,
                    textAlign = TextAlign.End,
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(verticalScroll)
                .let { if (!wrap) it.horizontalScroll(horizontalScroll) else it }
        ) {
            TextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = 1.dp),
                textStyle = NotepadType.editorText,
                singleLine = false,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = NotepadColors.accent,
                    selectionColors = TextSelectionColors(
                        handleColor = NotepadColors.accent,
                        backgroundColor = NotepadColors.accentTint20,
                    ),
                ),
            )
        }
    }
}
