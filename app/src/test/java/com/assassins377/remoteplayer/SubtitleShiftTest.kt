package com.assassins377.remoteplayer

import com.assassins377.remoteplayer.player.SubtitleShift
import com.assassins377.remoteplayer.ui.player.PlayerMath
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleShiftTest {
    @Test fun srt() {
        val s = "1\n00:00:01,000 --> 00:00:02,500\nПривет 00:00:09,000\n\n2\n01:59:59,900 --> 02:00:00,100\nx\n"
        assertEquals("1\n00:00:01,500 --> 00:00:03,000\nПривет 00:00:09,000\n\n2\n02:00:00,400 --> 02:00:00,600\nx\n", SubtitleShift.shift(s, 500, "srt"))
        assertEquals("1\n00:00:00,000 --> 00:00:00,500\nПривет 00:00:09,000\n\n2\n01:59:57,900 --> 01:59:58,100\nx\n", SubtitleShift.shift(s, -2000, "srt"))
    }
    @Test fun vtt() {
        val s = "WEBVTT\n\n00:01.000 --> 00:04.000 align:start\nHi\n"
        assertEquals("WEBVTT\n\n00:02.250 --> 00:05.250 align:start\nHi\n", SubtitleShift.shift(s, 1250, "vtt"))
    }
    @Test fun ass() {
        val s = "[Events]\nDialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,Text, with comma\n"
        assertEquals("[Events]\nDialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,Text, with comma\n", SubtitleShift.shift(s, -500, "ass"))
    }
    @Test fun formatDelay() {
        assertEquals("+0,5 с", PlayerMath.formatDelay(500)); assertEquals("−2 с", PlayerMath.formatDelay(-2000)); assertEquals("0 с", PlayerMath.formatDelay(0))
    }
}
