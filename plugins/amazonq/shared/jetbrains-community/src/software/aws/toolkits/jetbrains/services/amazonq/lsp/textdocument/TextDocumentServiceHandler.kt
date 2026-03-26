// Copyright 2025 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.amazonq.lsp.textdocument

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import software.amazon.q.core.utils.getLogger
import software.amazon.q.core.utils.tryOrNull
import software.amazon.q.core.utils.warn
import software.aws.toolkits.jetbrains.services.amazonq.lsp.AmazonQLanguageServer
import software.aws.toolkits.jetbrains.services.amazonq.lsp.AmazonQLspService
import software.aws.toolkits.jetbrains.services.amazonq.lsp.model.aws.chat.ACTIVE_EDITOR_CHANGED_NOTIFICATION
import software.aws.toolkits.jetbrains.services.amazonq.lsp.util.LspEditorUtil.getCursorState
import software.aws.toolkits.jetbrains.services.amazonq.lsp.util.LspEditorUtil.toUriString

class TextDocumentServiceHandler(
    private val project: Project,
    private val cs: CoroutineScope,
) : FileDocumentManagerListener,
    FileEditorManagerListener,
    BulkFileListener,
    DocumentListener,
    Disposable {

    init {
        // didOpen & didClose events
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            this
        )

        // didChange events
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            this
        )

        // didSave events
        project.messageBus.connect(this).subscribe(
            FileDocumentManagerListener.TOPIC,
            this
        )

        // open files on startup
        cs.launch {
            val fileEditorManager = FileEditorManager.getInstance(project)
            fileEditorManager.selectedFiles.forEach { file ->
                handleFileOpened(file)
            }
        }
    }

    private fun handleFileOpened(file: VirtualFile) {
        if (file.getUserData(KEY_REAL_TIME_EDIT_LISTENER) == null) {
            val listener = object : DocumentListener {
                // Both callbacks fire on the EDT — no synchronization needed.
                private var preChangeEndPosition: Position = Position(0, 0)

                override fun beforeDocumentChange(event: DocumentEvent) {
                    // Capture the range end in old-document coordinates BEFORE the edit is applied.
                    // After the edit, the document's line offsets no longer map correctly for
                    // multi-line deletions/replacements, so we must snapshot here.
                    preChangeEndPosition = offsetToPosition(event.document, event.offset + event.oldLength)
                }

                override fun documentChanged(event: DocumentEvent) {
                    realTimeEdit(event, preChangeEndPosition)
                }
            }
            val document = ApplicationManager.getApplication().runReadAction<Document?> {
                FileDocumentManager.getInstance().getDocument(file)
            }
            document?.addDocumentListener(listener, this)
            file.putUserData(KEY_REAL_TIME_EDIT_LISTENER, listener)

            trySendIfValid { languageServer ->
                toUriString(file)?.let { uri ->
                    languageServer.textDocumentService.didOpen(
                        DidOpenTextDocumentParams().apply {
                            textDocument = TextDocumentItem().apply {
                                this.uri = uri
                                text = file.inputStream.readAllBytes().decodeToString()
                                languageId = file.fileType.name.lowercase()
                                version = file.modificationStamp.toInt()
                            }
                        }
                    )
                }
            }
        }
    }

    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val uri = toUriString(file) ?: return
        // Capture text on the calling thread before the coroutine runs.
        val capturedText = document.text

        // Flush pending changes and then send didSave in the same coroutine so the server
        // always receives all incremental edits before the save notification.
        cs.launch {
            flushPendingChangesForUri(uri)
            AmazonQLspService.executeAsyncIfRunning(project) { languageServer ->
                try {
                    languageServer.textDocumentService.didSave(
                        DidSaveTextDocumentParams().apply {
                            textDocument = TextDocumentIdentifier().apply { this.uri = uri }
                            // TODO: should respect `textDocumentSync.save.includeText` server capability config
                            text = capturedText
                        }
                    )
                } catch (e: Exception) {
                    LOG.warn { "Failed to send didSave for $uri: $e" }
                }
            }
        }
    }

    override fun after(events: MutableList<out VFileEvent>) {
        events.filterIsInstance<VFileContentChangeEvent>().forEach { event ->
            val document = FileDocumentManager.getInstance().getCachedDocument(event.file) ?: return@forEach

            handleFileOpened(event.file)
            trySendIfValid { languageServer ->
                toUriString(event.file)?.let { uri ->
                    languageServer.textDocumentService.didChange(
                        DidChangeTextDocumentParams().apply {
                            textDocument = VersionedTextDocumentIdentifier().apply {
                                this.uri = uri
                                version = document.modificationStamp.toInt()
                            }
                            contentChanges = listOf(
                                TextDocumentContentChangeEvent().apply {
                                    text = document.text
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    override fun fileOpened(
        source: FileEditorManager,
        file: VirtualFile,
    ) {
        handleFileOpened(file)
    }

    override fun fileClosed(
        source: FileEditorManager,
        file: VirtualFile,
    ) {
        val listener = file.getUserData(KEY_REAL_TIME_EDIT_LISTENER)
        if (listener != null) {
            file.putUserData(KEY_REAL_TIME_EDIT_LISTENER, null)
            ApplicationManager.getApplication().runReadAction {
                tryOrNull {
                    FileDocumentManager.getInstance().getCachedDocument(file)?.removeDocumentListener(listener)
                }
            }

            val uri = toUriString(file) ?: return

            // Flush pending changes and then send didClose in the same coroutine so the server
            // always receives all incremental edits before the close notification.
            cs.launch {
                flushPendingChangesForUri(uri)
                AmazonQLspService.executeAsyncIfRunning(project) { languageServer ->
                    try {
                        languageServer.textDocumentService.didClose(
                            DidCloseTextDocumentParams().apply {
                                textDocument = TextDocumentIdentifier().apply { this.uri = uri }
                            }
                        )
                    } catch (e: Exception) {
                        LOG.warn { "Failed to send didClose for $uri: $e" }
                    }
                }
            }
        }
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        handleActiveEditorChange(event.newEditor)
    }

    private fun handleActiveEditorChange(fileEditor: FileEditor?) {
        val editor = (fileEditor as? TextEditor)?.editor ?: return
        val virtualFile = editor.virtualFile ?: return // Return early if no file
        handleFileOpened(virtualFile)

        // Extract text editor if it's a TextEditor, otherwise null
        val textDocumentIdentifier = TextDocumentIdentifier(toUriString(virtualFile))
        val cursorState = getCursorState(editor)

        val params = mapOf(
            "textDocument" to textDocumentIdentifier,
            "cursorState" to cursorState
        )

        // Send notification to the language server
        cs.launch {
            AmazonQLspService.executeAsyncIfRunning(project) { _ ->
                rawEndpoint.notify(ACTIVE_EDITOR_CHANGED_NOTIFICATION, params)
            }
        }
    }

    /**
     * Pending incremental changes per document URI, accumulated between debounce flushes.
     */
    private data class PendingChange(
        val uri: String,
        val changes: MutableList<TextDocumentContentChangeEvent> = mutableListOf(),
        var latestVersion: Int = 0,
    )

    private val pendingChanges = ConcurrentHashMap<String, PendingChange>()
    private val pendingFlushJobs = ConcurrentHashMap<String, Job>()
    private val changeMutex = Mutex()

    private fun realTimeEdit(event: DocumentEvent, preChangeEndPosition: Position) {
        val vFile = FileDocumentManager.getInstance().getFile(event.document) ?: return
        val uri = toUriString(vFile) ?: return

        val changeEvent = TextDocumentContentChangeEvent().apply {
            range = Range(
                // Start offset is before the edit — identical in old and new document coordinates.
                offsetToPosition(event.document, event.offset),
                // End position was captured before the edit in beforeDocumentChange; using it here
                // avoids incorrect line mapping when the replaced text contained newlines.
                preChangeEndPosition,
            )
            text = event.newFragment.toString()
            rangeLength = event.oldLength
        }
        val version = event.document.modificationStamp.toInt()

        cs.launch {
            changeMutex.withLock {
                val pending = pendingChanges.getOrPut(uri) { PendingChange(uri) }
                pending.changes.add(changeEvent)
                pending.latestVersion = version

                // Cancel any existing flush job for this URI and start a new one
                pendingFlushJobs[uri]?.cancel()
                pendingFlushJobs[uri] = cs.launch {
                    delay(DEBOUNCE_MS)
                    flushChanges(uri)
                }
            }
        }
    }

    private suspend fun flushChanges(uri: String) {
        val pending = changeMutex.withLock {
            pendingFlushJobs.remove(uri)
            pendingChanges.remove(uri)
        } ?: return

        AmazonQLspService.executeAsyncIfRunning(project) { languageServer ->
            try {
                languageServer.textDocumentService.didChange(
                    DidChangeTextDocumentParams().apply {
                        textDocument = VersionedTextDocumentIdentifier().apply {
                            this.uri = pending.uri
                            version = pending.latestVersion
                        }
                        contentChanges = pending.changes
                    }
                )
            } catch (e: Exception) {
                LOG.warn { "Failed to flush batched didChange for $uri: $e" }
            }
        }
    }

    /**
     * Flushes any pending debounced changes for the given URI immediately.
     * Called before save and close to ensure the server has up-to-date content.
     */
    private suspend fun flushPendingChangesForUri(uri: String) {
        changeMutex.withLock {
            pendingFlushJobs.remove(uri)?.cancel()
        }
        flushChanges(uri)
    }

    companion object {
        private val KEY_REAL_TIME_EDIT_LISTENER = Key.create<DocumentListener>("amazonq.textdocument.realtimeedit.listener")
        private val LOG = getLogger<TextDocumentServiceHandler>()

        /** Debounce window for batching incremental edits before sending to the LSP server. */
        internal const val DEBOUNCE_MS = 100L

        /**
         * Converts an absolute document offset to an LSP [Position] (0-based line and character).
         */
        internal fun offsetToPosition(document: Document, offset: Int): Position {
            val clampedOffset = offset.coerceIn(0, document.textLength)
            val line = document.getLineNumber(clampedOffset)
            val character = clampedOffset - document.getLineStartOffset(line)
            return Position(line, character)
        }
    }

    override fun dispose() {
        // Cancel all pending flush jobs on disposal
        pendingFlushJobs.values.forEach { it.cancel() }
        pendingFlushJobs.clear()
        pendingChanges.clear()
    }

    private fun trySendIfValid(runnable: (AmazonQLanguageServer) -> Unit) {
        cs.launch {
            AmazonQLspService.executeAsyncIfRunning(project) { languageServer ->
                try {
                    runnable(languageServer)
                } catch (e: Exception) {
                    LOG.warn { "Invalid document: $e" }
                }
            }
        }
    }
}
