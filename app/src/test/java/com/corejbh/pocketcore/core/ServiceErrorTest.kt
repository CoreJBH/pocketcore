package com.corejbh.pocketcore.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warning a refused foreground service leaves on the NODE tab (versionCode
 * 22).
 *
 * `startForegroundService` used to fail into a log line, so a node that Android
 * could stop at any moment while the screen was off looked exactly like a
 * protected one. The refusal is now part of the state the screen draws, and the
 * exception that caused it is part of the sentence: on Android 12 and newer the
 * common cause is a start that came from the background
 * (`ForegroundServiceStartNotAllowedException`), and a reader of a bug report
 * needs to know which kind of refusal it was.
 */
class ServiceErrorTest {

    @Test
    fun theSentenceNamesTheExceptionAndWhatItMeans() {
        val message = serviceErrorMessage(
            IllegalStateException("Not allowed to start service Intent")
        )
        assertTrue(message.startsWith("Android did not start the service that keeps the node running"))
        assertTrue(message.contains("IllegalStateException: Not allowed to start service Intent"))
        assertTrue(message.contains("can be stopped while this app is in the background"))
        assertTrue(message.contains("press START again"))
    }

    @Test
    fun anExceptionWithoutAMessageStillReadsAsASentence() {
        val message = serviceErrorMessage(RuntimeException())
        assertTrue(message.contains("RuntimeException: no reason was given"))
        // No dangling separator or double space where the missing message was.
        assertTrue(!message.contains(": )"))
        assertTrue(!message.contains("  "))
    }

    @Test
    fun aBlankMessageIsTreatedLikeAMissingOne() {
        val message = serviceErrorMessage(RuntimeException("   "))
        assertTrue(message.contains("RuntimeException: no reason was given"))
    }
}
