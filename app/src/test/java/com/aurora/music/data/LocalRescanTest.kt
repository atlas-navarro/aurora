package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRescanTest {
    @Test fun countsAddedAndRemovedSongsByKey() {
        val result = LocalRescan.result(setOf("/m/a.flac", "/m/b.flac", "/m/c.flac"), setOf("/m/b.flac", "/m/c.flac", "/m/d.flac", "/m/e.flac"))
        assertEquals(LocalRescanResult(added = 2, removed = 1, total = 4), result)
        assertTrue(result.changed)
    }

    @Test fun anUnchangedLibraryReportsNoChange() {
        val songs = setOf("/m/a.flac", "/m/b.flac")
        val result = LocalRescan.result(songs, songs)
        assertEquals(LocalRescanResult(0, 0, 2), result)
        assertFalse(result.changed)
    }

    @Test fun scanRootsDropNestedDuplicateAndBlankFolders() {
        val roots = LocalRescan.roots(listOf("/storage/emulated/0/Music/Album", "/storage/emulated/0/Music/", "",
            "/storage/emulated/0/Music", "/storage/emulated/0/Download", "/storage/emulated/0/Music/Album/CD1"))
        assertEquals(listOf("/storage/emulated/0/Music", "/storage/emulated/0/Download"), roots)
    }

    @Test fun wholeDeviceKeepsEverythingButExcludedFolders() {
        val excluded = listOf("/storage/emulated/0/Android/media/com.whatsapp/")
        assertTrue(LocalRescan.inScope("/storage/emulated/0/Music/a.flac", emptyList(), emptyList()))
        assertTrue(LocalRescan.inScope("/storage/emulated/0/Music/a.flac", emptyList(), excluded))
        assertFalse(LocalRescan.inScope("/storage/emulated/0/Android/media/com.whatsapp/Notes/b.opus", emptyList(), excluded))
        assertTrue(LocalRescan.inScope("/storage/emulated/0/Android/media/com.whatsapp2/b.opus", emptyList(), excluded))
    }

    @Test fun chosenFoldersKeepOnlyTheirOwnFilesMinusExclusions() {
        val folders = listOf("/storage/emulated/0/Music", "/storage/1A2B-3C4D/FLAC")
        val excluded = listOf("/storage/emulated/0/Music/Podcasts")
        assertTrue(LocalRescan.inScope("/storage/emulated/0/Music/Album/a.flac", folders, excluded))
        assertTrue(LocalRescan.inScope("/storage/1a2b-3c4d/flac/b.flac", folders, excluded))
        assertFalse(LocalRescan.inScope("/storage/emulated/0/Ringtones/c.ogg", folders, excluded))
        assertFalse(LocalRescan.inScope("/storage/emulated/0/Music2/c.ogg", folders, excluded))
        assertFalse(LocalRescan.inScope("/storage/emulated/0/Music/Podcasts/ep1.mp3", folders, excluded))
        assertFalse(LocalRescan.inScope("", folders, excluded))
    }

    @Test fun anIncludedFolderInsideAnExcludedOneComesBack() {
        val folders = listOf("/m", "/m/skip/keep")
        assertTrue(LocalRescan.inScope("/m/skip/keep/a.flac", folders, listOf("/m/skip")))
        assertFalse(LocalRescan.inScope("/m/skip/other.flac", folders, listOf("/m/skip")))
        assertFalse(LocalRescan.inScope("/m/a.flac", listOf("/m"), listOf("/m")))
    }

    @Test fun keptFoldersSurviveScopeUnlessExcludedThemselves() {
        val kept = listOf("/storage/emulated/0/Music/Aurora")
        val song = "/storage/emulated/0/Music/Aurora/song.m4a"
        assertTrue(LocalRescan.inScope(song, listOf("/storage/emulated/0/FLAC"), emptyList(), kept))
        assertTrue(LocalRescan.inScope(song, emptyList(), listOf("/storage/emulated/0/Music"), kept))
        assertFalse(LocalRescan.inScope(song, emptyList(), listOf("/storage/emulated/0/Music/Aurora"), kept))
        assertFalse(LocalRescan.inScope("/storage/emulated/0/Music/x.mp3", emptyList(), listOf("/storage/emulated/0/Music"), kept))
    }

    @Test fun scanRootsKeepSiblingsThatOnlyShareAPrefix() {
        assertEquals(listOf("/sd/Music", "/sd/Music2"), LocalRescan.roots(listOf("/sd/Music2", "/sd/Music")))
    }
}
