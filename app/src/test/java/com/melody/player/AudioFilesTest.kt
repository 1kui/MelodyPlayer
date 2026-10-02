package com.melody.player

import com.melody.player.core.AudioFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「什么算音乐」的判定。
 *
 * 限定文件夹扫描全靠这一条：判漏了用户的歌比多收几个文件严重得多，
 * 所以白名单要够宽、MIME 与扩展名要取或。
 */
class AudioFilesTest {

    @Test
    fun `extension is lowercased and dot-free`() {
        assertEquals("flac", AudioFiles.extensionOf("Song.FLAC"))
        assertEquals("mp3", AudioFiles.extensionOf("a.b.c.mp3"))
    }

    @Test
    fun `names without a usable extension report none`() {
        assertEquals("", AudioFiles.extensionOf("没有扩展名"))
        assertEquals("", AudioFiles.extensionOf(".隐藏文件"))   // 点开头的是隐藏文件，不是扩展名
        assertEquals("", AudioFiles.extensionOf("尾点."))       // 结尾一个点不算扩展名
        assertEquals("", AudioFiles.extensionOf(""))
    }

    @Test
    fun `common music formats are recognised regardless of case`() {
        listOf("a.mp3", "a.FLAC", "a.wav", "a.m4a", "a.opus", "a.ape", "a.dsf", "a.wma")
            .forEach { assertTrue(it, AudioFiles.isAudioName(it)) }
    }

    @Test
    fun `video and document extensions are rejected`() {
        listOf("a.mp4", "a.mkv", "a.jpg", "a.txt", "a.lrc", "a.pdf", "a")
            .forEach { assertFalse(it, AudioFiles.isAudioName(it)) }
    }

    @Test
    fun `kwm is deliberately not treated as audio`() {
        // .kwm 是加密容器，有独立的解密流程；在曲库里当普通音频会读出噪声
        assertFalse(AudioFiles.isAudioName("a.kwm"))
    }

    @Test
    fun `audio mime wins even when the name says nothing`() {
        assertTrue(AudioFiles.isAudio("audio/mpeg", "无扩展名"))
        assertTrue(AudioFiles.isAudio("AUDIO/FLAC", "无扩展名"))
        assertTrue(AudioFiles.isAudio("audio", "无扩展名"))
        assertTrue(AudioFiles.isAudio("audio/mpeg; charset=utf-8", "无扩展名"))
    }

    @Test
    fun `octet-stream still passes when the extension is music`() {
        // 这是限定文件夹扫描最容易翻车的地方：不少提供器对 flac/ape 只给
        // application/octet-stream，只认 MIME 会把真歌悄悄漏掉
        assertTrue(AudioFiles.isAudio("application/octet-stream", "song.flac"))
        assertTrue(AudioFiles.isAudio(null, "song.ape"))
    }

    @Test
    fun `unrelated mime with non music name is rejected`() {
        assertFalse(AudioFiles.isAudio("video/mp4", "clip.mp4"))
        assertFalse(AudioFiles.isAudio("application/octet-stream", "cover.jpg"))
        assertFalse(AudioFiles.isAudio(null, "notes.txt"))
        assertFalse(AudioFiles.isAudio(null, ""))
    }

    @Test
    fun `only dot directories are skipped`() {
        assertTrue(AudioFiles.shouldSkipDirectory(".thumbnails"))
        assertTrue(AudioFiles.shouldSkipDirectory(""))
        assertTrue(AudioFiles.shouldSkipDirectory("."))
        // 普通目录哪怕名字古怪也不能跳过 —— 漏歌比多扫一层严重得多
        assertFalse(AudioFiles.shouldSkipDirectory("Music2"))
        assertFalse(AudioFiles.shouldSkipDirectory("我的音乐"))
        assertFalse(AudioFiles.shouldSkipDirectory("album (1)"))
    }
}