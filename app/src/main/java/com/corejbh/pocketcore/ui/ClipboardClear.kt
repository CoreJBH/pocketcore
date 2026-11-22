package com.corejbh.pocketcore.ui

/**
 * Whether the timed clear of a sensitive copy may run.
 *
 * The clear waits a minute and then asks whether the clipboard still holds
 * exactly what was copied, so a newer copy - the user's own, or another
 * app's - is never wiped. On Android 10 to 12 that question cannot always be
 * asked: a background app's `getPrimaryClip()` returns null, so a strict
 * comparison would always abort and the sensitive copy would stay on the
 * clipboard forever, exactly what the timer exists to prevent.
 *
 * [currentText] null therefore means "could not be read", and the clear runs
 * without the comparison. A readable clipboard is compared as before.
 */
internal object ClipboardClear {

    fun shouldClear(currentText: String?, copied: String): Boolean =
        currentText == null || currentText == copied
}
