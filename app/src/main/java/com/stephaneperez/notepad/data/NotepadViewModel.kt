package com.stephaneperez.notepad.data

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stephaneperez.notepad.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file entry surfaced by the in-app "Open document" list (see README §Platform adaptations). */
data class LocalFileEntry(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val modified: String,
)

class NotepadViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesRepository(application)

    private val _uiState = MutableStateFlow(NotepadUiState())
    val uiState: StateFlow<NotepadUiState> = _uiState.asStateFlow()

    private val _fileList = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    val fileList: StateFlow<List<LocalFileEntry>> = _fileList.asStateFlow()

    init {
        viewModelScope.launch {
            val wrap = prefs.currentWrap()
            val lineNumbers = prefs.currentLineNumbers()
            _uiState.update { it.copy(wrap = wrap, lineNumbers = lineNumbers) }

            val lastUriString = prefs.currentLastUri()
            if (lastUriString != null) {
                val uri = runCatching { Uri.parse(lastUriString) }.getOrNull()
                val restored = uri?.let { loadDocument(it) } ?: false
                if (!restored) {
                    // Dead reference (deleted file, revoked permission, malformed URI,
                    // or now too large) — don't keep retrying it on every future
                    // launch; start with a fresh untitled buffer instead.
                    prefs.setLastUri(null)
                }
            }
        }
    }

    // ---- Text editing --------------------------------------------------

    fun onTextChanged(newText: String) {
        _uiState.update { it.copy(text = newText, dirty = true) }
    }

    // ---- New / Open / Save --------------------------------------------

    /** Called by the "New" app-bar action. */
    fun requestNew() {
        if (_uiState.value.dirty) {
            _uiState.update { it.copy(confirm = PendingAction.NEW) }
        } else {
            performNew()
        }
    }

    /** Called by the "Open" app-bar action. */
    fun requestOpen() {
        if (_uiState.value.dirty) {
            _uiState.update { it.copy(confirm = PendingAction.OPEN) }
        } else {
            openBrowser()
        }
    }

    private fun performNew() {
        _uiState.update {
            NotepadUiState(
                wrap = it.wrap,
                lineNumbers = it.lineNumbers,
            )
        }
        viewModelScope.launch { prefs.setLastUri(null) }
    }

    private fun openBrowser() {
        loadFileList()
        _uiState.update { it.copy(browsing = true, menuOpen = false) }
    }

    fun dismissBrowser() {
        _uiState.update { it.copy(browsing = false) }
    }

    /** Confirmation dialog actions. */
    fun onDialogCancel() {
        _uiState.update { it.copy(confirm = null) }
    }

    fun onDialogDiscard() {
        val pending = _uiState.value.confirm
        _uiState.update { it.copy(confirm = null) }
        when (pending) {
            PendingAction.NEW -> performNew()
            PendingAction.OPEN -> openBrowser()
            null -> Unit
        }
    }

    fun onDialogSaveFirst() {
        if (!save()) {
            // Keep the dialog up (with the failure toast already set by save()) rather
            // than proceeding to New/Open and silently losing the buffer — this was the
            // main data-loss bug a second-opinion review caught, see DEVELOPMENT.md.
            return
        }
        resumePendingAction()
    }

    /**
     * Clears the pending New/Open action from the unsaved-changes dialog and executes
     * it — called only once a save has actually succeeded. A no-op if there's no
     * pending action (e.g. an ordinary Save-As with no dialog involved).
     */
    private fun resumePendingAction() {
        val pending = _uiState.value.confirm
        _uiState.update { it.copy(confirm = null) }
        when (pending) {
            PendingAction.NEW -> performNew()
            PendingAction.OPEN -> openBrowser()
            null -> Unit
        }
    }

    /** User picked a row in the Open-document overlay, or a document from the system picker. */
    fun openDocument(uri: Uri) {
        if (!loadDocument(uri)) {
            // Failure toast (too large / decrypt failed / couldn't open) already set by
            // loadDocument() — don't close the browser or remember this URI as if it
            // had worked; let the person pick a different file instead.
            return
        }
        _uiState.update {
            it.copy(browsing = false, finding = false, query = "", replacement = "")
        }
        viewModelScope.launch { prefs.setLastUri(uri.toString()) }
    }

    /** Returns true if [uri] was successfully loaded into the buffer. */
    private fun loadDocument(uri: Uri): Boolean {
        val context = getApplication<Application>()

        // Bound checked against MAX_CONTAINER_BYTES, not MAX_PLAINTEXT_BYTES: at this
        // point we don't yet know if this is one of our encrypted files (whose
        // container is up to 33 bytes larger than its plaintext) or a legacy plain-text
        // file — using the plaintext-only bound here would wrongly reject a legitimate,
        // maximally-sized note of our own on reopen.
        val readResult = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                DocumentCodec.readBytesUpTo(stream, CryptoManager.MAX_CONTAINER_BYTES)
            }
        }

        if (readResult.exceptionOrNull() is DocumentCodec.TooLargeException) {
            _uiState.update { it.copy(toast = context.getString(R.string.toast_file_too_large)) }
            return false
        }
        val bytes = readResult.getOrNull() ?: return false // couldn't open at all — stay silent, as before

        val content = when (val result = DocumentCodec.decode(bytes)) {
            is DocumentCodec.Result.Ok -> result.text
            DocumentCodec.Result.TooLarge -> {
                _uiState.update { it.copy(toast = context.getString(R.string.toast_file_too_large)) }
                return false
            }
            DocumentCodec.Result.DecryptFailed -> {
                _uiState.update { it.copy(toast = context.getString(R.string.toast_decrypt_failed)) }
                return false
            }
        }

        val name = queryDisplayName(uri) ?: "untitled.txt"
        _uiState.update {
            it.copy(
                filename = name,
                uri = uri,
                text = content,
                dirty = false,
                filePath = pathForDisplay(uri, name),
            )
        }
        return true
    }

    private fun queryDisplayName(uri: Uri): String? {
        // file:// URIs (the in-app document list) have no ContentProvider to query —
        // without this, every file opened from that list showed up as "untitled.txt".
        if (uri.scheme == "file") return uri.lastPathSegment
        val context = getApplication<Application>()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        }.getOrNull()
    }

    private fun pathForDisplay(uri: Uri, name: String): String {
        // SAF URIs don't expose a real filesystem path; approximate the status-bar path
        // from the document tree/id when available, falling back to the filename alone.
        return runCatching {
            val docId = DocumentsContract.getDocumentId(uri)
            "/${docId.substringAfter(':', docId)}"
        }.getOrElse { "/$name" }
    }

    /**
     * Encrypts [text] and writes it to [uri]. Never touches UI state — callers decide
     * what to do with the result. Returns false on any failure (I/O error, revoked
     * permission, provider unavailable, `openOutputStream` returning null).
     *
     * Known limitation, accepted rather than solved: this opens in truncate mode
     * (`"wt"`), so a write that fails partway (disk full, provider error) can leave the
     * file empty or partially written rather than restoring the previous good content —
     * true atomic replace would need a temp-document-then-rename dance whose
     * reliability varies by SAF provider. See DEVELOPMENT.md, round 4.
     */
    private fun writeEncrypted(uri: Uri, text: String): Boolean {
        val context = getApplication<Application>()
        return runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return@runCatching false
            stream.use { it.write(CryptoManager.encrypt(text)) }
            true
        }.getOrElse { false }
    }

    private fun exceedsSizeLimit(text: String): Boolean =
        text.toByteArray(Charsets.UTF_8).size > CryptoManager.MAX_PLAINTEXT_BYTES

    /**
     * Save writes straight to the current file — no Save-as dialog, no confirmation
     * step. The buffer is always encrypted before it hits disk (see [CryptoManager]);
     * there is no way to save an Angerona file in plain text. Refuses to write buffers
     * over the 100 KB limit rather than silently truncating or crashing.
     *
     * Returns true on success. Callers that chain further actions after a save (e.g.
     * "Save first" in the unsaved-changes dialog) must check this and not proceed on
     * failure — an earlier version of this app didn't, which could silently discard the
     * buffer if the write failed; see README.
     */
    fun save(): Boolean {
        val state = _uiState.value
        val uri = state.uri
        val context = getApplication<Application>()

        if (uri == null) {
            // Untitled buffer: caller (UI) must launch ACTION_CREATE_DOCUMENT and then
            // call completeSaveAs(uri) with the result.
            return false
        }

        if (exceedsSizeLimit(state.text)) {
            _uiState.update { it.copy(toast = context.getString(R.string.toast_file_too_large)) }
            return false
        }

        val success = writeEncrypted(uri, state.text)
        _uiState.update {
            if (success) {
                it.copy(dirty = false, menuOpen = false, toast = context.getString(R.string.toast_saved, it.filePath))
            } else {
                it.copy(toast = context.getString(R.string.toast_save_failed))
            }
        }
        return success
    }

    /**
     * Called after the platform's ACTION_CREATE_DOCUMENT picker returns a destination.
     * Writes to [uri] first and only switches the app's current document over to it —
     * updating `uri`/`filename`/`filePath` — once that write has actually succeeded, so
     * a failed Save-As can't leave the UI pointing at a file that was never written.
     *
     * If this Save-As was triggered by the unsaved-changes dialog's "Save first" (for a
     * buffer that had never been saved), resumes the pending New/Open action only now,
     * after confirmed success — not the moment the picker returns a URI, which only
     * means a destination was chosen, not that anything was written there yet.
     */
    fun completeSaveAs(uri: Uri) {
        val context = getApplication<Application>()

        val text = _uiState.value.text
        if (exceedsSizeLimit(text)) {
            // Same check as save(): without it, an oversized buffer reached encrypt()'s
            // last-resort guard and surfaced as a generic "Could not save".
            _uiState.update { it.copy(toast = context.getString(R.string.toast_file_too_large)) }
            return
        }
        if (!writeEncrypted(uri, text)) {
            _uiState.update { it.copy(toast = context.getString(R.string.toast_save_failed)) }
            // Deliberately don't touch `confirm` here: if this came from "Save first",
            // the unsaved-changes dialog stays up (with the failure toast now visible)
            // instead of silently discarding the buffer — same principle as save()'s
            // own failure handling.
            return
        }

        // Persist the URI permission only now that the write actually succeeded —
        // avoids persisting access to a document we never actually wrote to.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }

        val name = queryDisplayName(uri) ?: _uiState.value.filename
        val filePath = pathForDisplay(uri, name)
        _uiState.update {
            it.copy(
                uri = uri,
                filename = name,
                filePath = filePath,
                dirty = false,
                menuOpen = false,
                toast = context.getString(R.string.toast_saved, filePath),
            )
        }
        viewModelScope.launch { prefs.setLastUri(uri.toString()) }
        resumePendingAction()
    }

    /** Take persistable permission right after ACTION_OPEN_DOCUMENT returns, before loading. */
    fun takePersistablePermission(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    // ---- Find & replace -------------------------------------------------

    fun toggleFind() {
        _uiState.update { it.copy(finding = !it.finding) }
    }

    fun closeFind() {
        _uiState.update { it.copy(finding = false) }
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query, matchIndex = 0) }
    }

    fun onReplacementChanged(replacement: String) {
        _uiState.update { it.copy(replacement = replacement) }
    }

    fun nextMatch() {
        _uiState.update {
            val total = it.totalMatches
            if (total == 0) it else it.copy(matchIndex = (it.matchIndex + 1) % total)
        }
    }

    fun previousMatch() {
        _uiState.update {
            val total = it.totalMatches
            if (total == 0) it else it.copy(matchIndex = (it.matchIndex - 1 + total) % total)
        }
    }

    /** Plain literal, case-sensitive substring replace-all. */
    fun replaceAll() {
        val state = _uiState.value
        val context = getApplication<Application>()
        if (state.query.isEmpty()) {
            _uiState.update { it.copy(toast = context.getString(R.string.toast_no_matches)) }
            return
        }
        val occurrences = state.totalMatches
        if (occurrences == 0) {
            _uiState.update { it.copy(toast = context.getString(R.string.toast_no_matches)) }
            return
        }
        val newText = state.text.replace(state.query, state.replacement)
        val label = context.resources.getQuantityString(R.plurals.toast_replaced, occurrences, occurrences)
        _uiState.update {
            it.copy(text = newText, dirty = true, matchIndex = 0, toast = label)
        }
    }

    // ---- Overflow menu ---------------------------------------------------

    fun toggleMenu() {
        _uiState.update { it.copy(menuOpen = !it.menuOpen) }
    }

    fun dismissMenu() {
        _uiState.update { it.copy(menuOpen = false) }
    }

    fun toggleWrap() {
        val newValue = !_uiState.value.wrap
        _uiState.update { it.copy(wrap = newValue) }
        viewModelScope.launch { prefs.setWrap(newValue) }
    }

    fun toggleLineNumbers() {
        if (_uiState.value.wrap) return // inert while wrap is on
        val newValue = !_uiState.value.lineNumbers
        _uiState.update { it.copy(lineNumbers = newValue) }
        viewModelScope.launch { prefs.setLineNumbers(newValue) }
    }

    // ---- Toast -----------------------------------------------------------

    fun consumeToast() {
        _uiState.update { it.copy(toast = "") }
    }

    // ---- Back navigation ---------------------------------------------------
    // Order: overflow menu -> find strip -> Open overlay -> dialog (cancel) -> system back.
    // Returns true if this call consumed the back press.
    fun onBackPressed(): Boolean {
        val state = _uiState.value
        return when {
            state.menuOpen -> { dismissMenu(); true }
            state.finding -> { closeFind(); true }
            state.browsing -> { dismissBrowser(); true }
            state.confirm != null -> { onDialogCancel(); true }
            else -> false
        }
    }

    // ---- In-app document list (stands in for the system picker; see README) -------

    private fun loadFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val docsDir = context.getExternalFilesDir(null) ?: return@launch
            val entries = docsDir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                ?.sortedBy { it.name }
                ?.map { f ->
                    LocalFileEntry(
                        uri = Uri.fromFile(f),
                        name = f.name,
                        sizeBytes = f.length(),
                        modified = android.text.format.DateFormat.format("d MMM yyyy", f.lastModified()).toString(),
                    )
                } ?: emptyList()
            _fileList.value = entries
        }
    }
}
