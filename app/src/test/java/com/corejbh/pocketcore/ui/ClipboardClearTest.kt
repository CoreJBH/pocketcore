package com.corejbh.pocketcore.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timed clear of a sensitive copy: where the clipboard can be read, a
 * newer copy is left alone; where it cannot (Android 10-12 in the background),
 * the clear runs anyway instead of the copy surviving forever.
 */
class ClipboardClearTest {

    @Test
    fun theSameTextIsCleared() {
        assertTrue(ClipboardClear.shouldClear("descriptor-wpkh-123", "descriptor-wpkh-123"))
    }

    @Test
    fun aNewerCopyIsLeftAlone() {
        assertFalse(ClipboardClear.shouldClear("something the user copied later", "descriptor-wpkh-123"))
    }

    @Test
    fun anUnreadableClipboardIsCleared() {
        // getPrimaryClip() answers null to a background app on Android 10-12:
        // the comparison cannot run, and refusing to clear is what kept the
        // copy on the clipboard.
        assertTrue(ClipboardClear.shouldClear(null, "descriptor-wpkh-123"))
    }
}
