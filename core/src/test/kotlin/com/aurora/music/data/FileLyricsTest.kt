package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

class FileLyricsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun syltBytes(encoding: Int, vararg entries: Pair<String, Int>): ByteArray {
        val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        val out = ByteArrayOutputStream()
        for ((text, ms) in entries) {
            out.write(text.toByteArray(charset))
            out.write(if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0))
            out.write(ByteBuffer.allocate(4).putInt(ms).array())
        }
        return out.toByteArray()
    }

    private fun syltFrame(encoding: Int, format: Int, vararg entries: Pair<String, Int>) =
        ID3v24Frame("SYLT").apply { body = FrameBodySYLT(encoding, "eng", format, 1, "", syltBytes(encoding, *entries)) }

    @Test fun lrcTextIsSyncedAndPlainTextDropsLrcMetadata() {
        val synced = FileLyrics.fromText("﻿[ar:Someone]\r\n[00:01.50]First\r\n[00:03.00]Second", "Embedded")!!
        assertTrue(synced.synced)
        assertEquals(listOf(1500L, 3000L), synced.lines.map { it.startMs })
        assertEquals(listOf("First", "Second"), synced.lines.map { it.text })

        val plain = FileLyrics.fromText("[ar:Someone]\nVerse one\n\nVerse two", "Embedded")!!
        assertFalse(plain.synced)
        assertEquals(listOf("Verse one", "", "Verse two"), plain.lines.map { it.text })
        assertNull(FileLyrics.fromText("  \n[ti:Only metadata]\n", "Embedded"))
    }

    @Test fun id3UnsyncedLyricsReadAsPlainOrAsLrcWhenTimed() {
        val plain = ID3v24Tag().apply { setField(FieldKey.LYRICS, "Line one\nLine two") }
        val read = FileLyrics.fromTag(plain)!!
        assertFalse(read.synced)
        assertEquals("Embedded", read.source)
        assertEquals(listOf("Line one", "Line two"), read.lines.map { it.text })

        val timed = ID3v24Tag().apply { setField(FieldKey.LYRICS, "[00:02.00]Timed") }
        assertEquals(2000L, FileLyrics.fromTag(timed)!!.lines.single().startMs)
    }

    @Test fun syltWinsOverPlainUsltAndKeepsLineTiming() {
        val tag = ID3v24Tag().apply {
            setField(FieldKey.LYRICS, "Plain words")
            setFrame(syltFrame(0, 2, "Hello" to 1000, "World" to 2500))
        }
        val read = FileLyrics.fromTag(tag)!!
        assertTrue(read.synced)
        assertEquals(listOf(1000L to "Hello", 2500L to "World"), read.lines.map { it.startMs to it.text })
    }

    @Test fun syltDecodesUtf16WithBomAndUtf16Be() {
        for (encoding in listOf(1, 2)) {
            val lines = FileLyrics.fromSylt(FrameBodySYLT(encoding, "eng", 2, 1, "", syltBytes(encoding, "Grüße" to 500, "日本" to 900)))!!.lines
            assertEquals(listOf("Grüße", "日本"), lines.map { it.text })
            assertEquals(listOf(500L, 900L), lines.map { it.startMs })
        }
    }

    @Test fun wordTimedSyltGroupsIntoLinesWithWordTiming() {
        val body = FrameBodySYLT(3, "eng", 2, 1, "", syltBytes(3, "Hel" to 1000, "lo " to 1200, "you" to 1400, "\nNext" to 3000, " line" to 3300))
        val lines = FileLyrics.fromSylt(body)!!.lines
        assertEquals(listOf("Hello you", "Next line"), lines.map { it.text })
        assertEquals(listOf(1000L, 1200L, 1400L), lines[0].words.map { it.startMs })
        assertEquals(3000L, lines[0].words.last().endMs)
        assertEquals(listOf("Next", "line"), lines[1].words.map { it.text.trim() })
    }

    @Test fun mpegFrameTimestampsAreSkipped() {
        assertNull(FileLyrics.fromSylt(FrameBodySYLT(0, "eng", 1, 1, "", syltBytes(0, "x" to 10))))
    }

    @Test fun vorbisAndMp4LyricsFieldsAreRead() {
        val vorbis = VorbisCommentTag.createNewTag().apply { setField("UNSYNCEDLYRICS", "Flac words") }
        assertEquals("Flac words", FileLyrics.fromTag(vorbis)!!.lines.single().text)
        val timedVorbis = VorbisCommentTag.createNewTag().apply { setField("LYRICS", "[00:04.00]Synced flac") }
        assertTrue(FileLyrics.fromTag(timedVorbis)!!.synced)
        val mp4 = Mp4Tag().apply { setField(FieldKey.LYRICS, "[00:01.00]Synced m4a") }
        assertEquals(1000L, FileLyrics.fromTag(mp4)!!.lines.single().startMs)
    }

    @Test fun syncedSidecarWinsAndAPlainOneStillShowsWhenTagsHaveNothing() {
        val audio = temp.newFile("Track.flac").apply { writeText("not audio") }
        File(audio.parentFile, "Track.lrc").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "[00:05.00]From sidecar".toByteArray(Charsets.UTF_16LE))
        val synced = FileLyrics.read(audio)!!
        assertEquals("LRC file", synced.source)
        assertEquals(5000L, synced.lines.single().startMs)

        File(audio.parentFile, "Track.lrc").writeBytes("Café plain".toByteArray(Charsets.ISO_8859_1))
        val plain = FileLyrics.read(audio)!!
        assertFalse(plain.synced)
        assertEquals("Café plain", plain.lines.single().text)
    }

    @Test fun lyricsAreReadFromARealMp3File() {
        val mp3 = temp.newFile("song.mp3")
        // ten silent mpeg-1 layer iii frames, 128 kbps 44.1 khz, so jaudiotagger accepts the file
        mp3.writeBytes(ByteArray(417 * 10).also { for (f in 0 until 10) byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0).copyInto(it, f * 417) })
        AudioFileIO.read(mp3).apply {
            tag = ID3v24Tag().apply {
                setField(FieldKey.LYRICS, "Plain only")
                setFrame(syltFrame(3, 2, "From the file" to 750))
            }
            commit()
        }
        val read = FileLyrics.read(mp3)!!
        assertTrue(read.synced)
        assertEquals(750L to "From the file", read.lines.single().let { it.startMs to it.text })
    }

    @Test fun fileOfResolvesPathsAndDownloadsButSkipsCueTracksAndRelativeServerPaths() {
        val audio = temp.newFile("a b.flac")
        val song = Song("id", "T", "A", "Al", "", 100)
        assertEquals(audio, FileLyrics.fileOf(song.copy(path = audio.path)))
        assertEquals(audio.canonicalFile, FileLyrics.fileOf(song.copy(streamUrl = audio.toURI().toString()))!!.canonicalFile)
        assertNull(FileLyrics.fileOf(song.copy(path = audio.path, streamUrl = CueClip(audio.toURI().toString(), 0, 5000).url)))
        assertNull(FileLyrics.fileOf(song.copy(path = "Artist/Album/a b.flac", streamUrl = "https://server/stream?id=1")))
    }

    @Test fun aFilesOwnLyricsBeatTheServerAndLookups() = runBlocking {
        val audio = temp.newFile("own.mp3")
        File(audio.parentFile, "own.lrc").writeText("[00:01.00]Mine")
        val backend = Proxy.newProxyInstance(MediaBackend::class.java.classLoader, arrayOf(MediaBackend::class.java)) { _, method, _ ->
            when (method.name) {
                "sourceType" -> ServerType.SUBSONIC
                "serverLyrics" -> Lyrics(listOf(LyricLine(0, "server")), true, "Server")
                else -> null
            }
        } as MediaBackend
        val repo = LyricsRepository({ backend }, lrclibEnabledProvider = { false })
        assertEquals("LRC file", repo.lyricsFor(Song("id", "T", "A", "Al", "", 100, path = audio.path))!!.source)
        assertEquals("Server", repo.lyricsFor(Song("id", "T", "A", "Al", "", 100))!!.source)
    }
}
