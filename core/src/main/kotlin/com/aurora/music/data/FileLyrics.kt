package com.aurora.music.data

import com.aurora.music.model.Song
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.framebody.FrameBodySYLT
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.logging.Level
import java.util.logging.Logger

// a file's own lyrics: a sidecar .lrc beside it, then its tags (sylt, uslt, vorbis lyrics, mp4 ©lyr)
object FileLyrics {
    // held so the level survives; jul only keeps loggers weakly
    @Suppress("unused") private val quiet =Logger.getLogger("org.jaudiotagger").apply { level = Level.OFF }

    private val META_LINE = Regex("""^\[[A-Za-z#]+:[^\]]*]$""")

    fun of(song: Song): Lyrics? = fileOf(song)?.let(::read)

    // cue tracks share one album file, so its lyrics belong to no single track
    fun fileOf(song: Song): File? {
        if (CueClip.of(song.streamUrl) != null) return null
        song.path.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isAbsolute && it.isFile }?.let { return it }
        if (!song.streamUrl.startsWith("file:")) return null
        return runCatching { File(URI(song.streamUrl)) }.getOrNull()?.takeIf { it.isFile }
    }

    fun read(file: File): Lyrics? {
        val sidecar = sidecar(file)?.let { runCatching { fromText(decode(it.readBytes()), "LRC file") }.getOrNull() }
        if (sidecar?.synced == true) return sidecar
        val tagged = runCatching { AudioFileIO.read(file).tag }.getOrNull()?.let(::fromTag)
        return if (tagged?.synced == true) tagged else sidecar ?: tagged
    }

    private fun sidecar(file: File): File? {
        val base = file.nameWithoutExtension
        return listOf("lrc", "LRC").map { File(file.parentFile, "$base.$it") }.firstOrNull { it.isFile }
    }

    fun fromTag(tag: Tag): Lyrics? {
        val sylt = runCatching { tag.getFields("SYLT") }.getOrNull().orEmpty()
            .firstNotNullOfOrNull { ((it as? AbstractID3v2Frame)?.body as? FrameBodySYLT)?.let(::fromSylt) }
        if (sylt != null) return sylt
        val texts = listOf<() -> String?>(
            { tag.getFirst(FieldKey.LYRICS) }, { tag.getFirst("UNSYNCEDLYRICS") }, { tag.getFirst("SYNCEDLYRICS") },
        ).mapNotNull { runCatching(it).getOrNull()?.takeIf(String::isNotBlank) }.distinct()
        val found = texts.mapNotNull { fromText(it, "Embedded") }
        return found.firstOrNull { it.synced } ?: found.firstOrNull()
    }

    // lrc text, or plain lines when it carries no timestamps
    fun fromText(text: String, source: String): Lyrics? {
        val clean = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (clean.isBlank()) return null
        val synced = LyricsRepository.parseLrc(clean)
        if (synced.any { it.text.isNotBlank() }) return Lyrics(synced, true, source)
        val plain = clean.lines().map(String::trim).filterNot { META_LINE.matches(it) }
        if (plain.all(String::isBlank)) return null
        return Lyrics(plain.map { LyricLine(-1, it) }, false, source)
    }

    // pairs of (terminated text, 4-byte time); an entry starting with a newline opens a line, so word-timed frames stay one line each
    fun fromSylt(body: FrameBodySYLT): Lyrics? {
        if (body.timeStampFormat != 2) return null // mpeg-frame stamps need the stream's frame rate
        val encoding = body.textEncoding.toInt()
        val wide = encoding == 1 || encoding == 2
        val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
        val data = body.lyrics ?: return null
        val entries = mutableListOf<Pair<Long, String>>()
        var i = 0
        while (i < data.size) {
            var end = i
            if (wide) while (end + 1 < data.size && (data[end].toInt() != 0 || data[end + 1].toInt() != 0)) end += 2
            else while (end < data.size && data[end].toInt() != 0) end++
            val at = end + if (wide) 2 else 1
            if (at + 4 > data.size) break
            entries += (ByteBuffer.wrap(data, at, 4).int.toLong() and 0xFFFFFFFFL) to String(data, i, end - i, charset)
            i = at + 4
        }
        if (entries.isEmpty()) return null
        val opens = { text: String -> text.startsWith('\n') || text.startsWith('\r') }
        val groups = if (entries.none { opens(it.second) }) entries.map { listOf(it) }
            else entries.fold(mutableListOf<MutableList<Pair<Long, String>>>()) { acc, entry ->
                if (acc.isEmpty() || opens(entry.second)) acc += mutableListOf(entry) else acc.last() += entry
                acc
            }
        val lines = groups.mapIndexed { g, group ->
            val start = group.first().first
            if (group.size == 1) return@mapIndexed LyricLine((start / 1000).toInt(), group.first().second.trim(), start)
            val nextLine = groups.getOrNull(g + 1)?.first()?.first ?: -1
            val words = group.mapIndexedNotNull { w, (ms, text) ->
                text.trimStart('\r', '\n').takeIf(String::isNotEmpty)?.let { LyricWord(ms, group.getOrNull(w + 1)?.first ?: nextLine, it) }
            }
            LyricsRepository.trimmedLine(start, words)
        }
        return if (lines.any { it.text.isNotBlank() }) Lyrics(lines.sortedBy { it.startMs }, true, "Embedded") else null
    }

    // sidecars come in whatever the writer used: utf-8/16 with a bom, plain utf-8, or a legacy code page
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }
}
