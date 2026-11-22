package com.corejbh.pocketcore.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Which snapshot files a new import replaces.
 *
 * A snapshot is gigabytes, so the folder is not allowed to keep every file ever
 * imported: the one in use survives, an older `.dat` and a `.part` a killed
 * import left behind do not (versionCode 20).
 */
class SnapshotFilesTest {

    private fun dir(): File = Files.createTempDirectory("snapshots").toFile()

    @Test
    fun keepsTheFileInUseAndListsTheRest() {
        val dir = dir()
        try {
            val keep = File(dir, "snapshot.dat").apply { writeText("current") }
            val older = File(dir, "older.dat").apply { writeText("old") }
            val partial = File(dir, "killed.dat.part").apply { writeText("half") }
            val unrelated = File(dir, "notes.txt").apply { writeText("keep me") }

            val stale = NodePaths.staleSnapshotFiles(dir, keep = keep).map { it.name }.sorted()
            assertEquals(listOf("killed.dat.part", "older.dat"), stale)
            assertTrue(unrelated.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Without a file to keep (a wipe or a failed import) every snapshot goes. */
    @Test
    fun withoutAFileToKeepEverySnapshotIsStale() {
        val dir = dir()
        try {
            val first = File(dir, "a.dat").apply { writeText("a") }
            val second = File(dir, "b.dat.part").apply { writeText("b") }
            val stale = NodePaths.staleSnapshotFiles(dir, keep = null).map { it.name }.sorted()
            assertEquals(listOf("a.dat", "b.dat.part"), stale)
            assertTrue(first.exists() && second.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aMissingDirectoryHasNothingStale() {
        val dir = File(dir(), "gone")
        assertTrue(NodePaths.staleSnapshotFiles(dir, keep = null).isEmpty())
    }
}
