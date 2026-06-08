package com.ghostdraft.bubble

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Writes transcribed text into the input field that is currently focused in
 * whatever app is in the foreground. This is the Android equivalent of the
 * Mac app's clipboard+Cmd-V injector — except here we can set the text
 * directly on the focused node, which is cleaner and doesn't clobber the
 * clipboard.
 *
 * The service holds no state and does no work on accessibility events; it
 * exists purely so the rest of the app can call [insertIntoFocusedField].
 */
class GhostDraftAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Accessibility service connected.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    /**
     * Inserts [text] at the cursor of the focused editable field.
     * @return [Result] describing what happened, so the caller can surface a hint.
     */
    fun insertIntoFocusedField(text: String): Result {
        if (text.isBlank()) return Result.NOTHING_TO_INSERT

        val root = rootInActiveWindow ?: return Result.NO_FOCUS
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return clipboardFallback(text, reason = Result.NO_FOCUS)

        if (!node.isEditable) {
            return clipboardFallback(text, reason = Result.NOT_EDITABLE)
        }

        val existing = node.text?.toString() ?: ""
        val (start, end) = selectionRange(node, existing.length)

        // Add a leading space if we're appending mid-word (e.g. cursor right
        // after a letter) so dictated words don't fuse onto the previous one.
        val needsLeadingSpace =
            start > 0 && !existing[start - 1].isWhitespace() && text.first().isLetterOrDigit()
        val insert = if (needsLeadingSpace) " $text" else text

        val newText = existing.substring(0, start) + insert + existing.substring(end)
        val setArgs = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
        }

        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        if (!ok) return clipboardFallback(text, reason = Result.SET_TEXT_FAILED)

        // Place the cursor right after the inserted text.
        val cursor = start + insert.length
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)

        return Result.INSERTED
    }

    private fun selectionRange(node: AccessibilityNodeInfo, len: Int): Pair<Int, Int> {
        val s = node.textSelectionStart
        val e = node.textSelectionEnd
        if (s < 0 || e < 0) return len to len // no selection -> append at end
        val lo = minOf(s, e).coerceIn(0, len)
        val hi = maxOf(s, e).coerceIn(0, len)
        return lo to hi
    }

    /**
     * Last resort: copy to clipboard and try ACTION_PASTE on the focused node.
     * If even that isn't possible, the text is at least on the clipboard so the
     * user can paste manually.
     */
    private fun clipboardFallback(text: String, reason: Result): Result {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("GhostDraft", text))

        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null && node.isEditable &&
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        ) {
            return Result.PASTED
        }
        Log.i(TAG, "Fell back to clipboard (reason=$reason).")
        return Result.COPIED_TO_CLIPBOARD
    }

    enum class Result {
        INSERTED,             // set directly on the focused field
        PASTED,               // pasted via ACTION_PASTE
        COPIED_TO_CLIPBOARD,  // couldn't paste; text is on the clipboard
        NO_FOCUS,             // no focused input found
        NOT_EDITABLE,         // focused node isn't an editable field
        SET_TEXT_FAILED,      // SET_TEXT rejected by the field
        NOTHING_TO_INSERT;    // empty/blank transcription

        val isSuccess: Boolean get() = this == INSERTED || this == PASTED
    }

    companion object {
        private const val TAG = "GhostDraftA11y"

        /** Live instance while the service is enabled, else null. */
        @Volatile
        var instance: GhostDraftAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }
}
