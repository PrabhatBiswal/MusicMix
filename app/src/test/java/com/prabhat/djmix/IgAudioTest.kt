package com.prabhat.djmix

import com.prabhat.djmix.instagram.IgAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class IgAudioTest {
    @Test fun linkToIdAndMediaPk() {
        assertEquals("ig_BsOGulcndj-", IgAudio.idFromText("look https://www.instagram.com/p/BsOGulcndj-/?igsh=abc"))
        assertEquals("ig_CxYz_-Abcde", IgAudio.idFromText("instagram.com/reel/CxYz_-Abcde"))
        assertNull(IgAudio.idFromText("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(1949525278281554174L, IgAudio.pk("BsOGulcndj-"))
        assertEquals(1949525278281554174L, IgAudio.pk("BsOGulcndj-AbCdEf")) // private-post suffix ignored
        assertEquals("https://www.instagram.com/reel/BsOGulcndj-/", IgAudio.url("ig_BsOGulcndj-"))
    }

    @Test fun parsePicksLargestVideoAndSongTitle() {
        val reel = """{"items":[{"video_duration":31.5,"video_versions":[{"width":720,"height":1280,"url":"u-small"},{"width":1080,"height":1920,"url":"u-big"}],
            "clips_metadata":{"music_info":{"music_asset_info":{"title":"Crazy Kiya Re","display_artist":"Sunidhi Chauhan"}}},
            "caption":{"text":"first line\nsecond"},"user":{"username":"x"}}]}"""
        val i = IgAudio.parse("ig_a", reel)
        assertEquals("u-big", i.url); assertEquals(31500L, i.durationMs); assertEquals("mp4", i.ext)
        assertEquals("Sunidhi Chauhan - Crazy Kiya Re", i.title)
        val carousel = """{"items":[{"carousel_media":[{"image_versions2":{}},{"video_versions":[{"width":1,"height":1,"url":"c"}],"video_duration":2}],"caption":{"text":"My clip \n more"}}]}"""
        val c = IgAudio.parse("ig_a", carousel)
        assertEquals("c", c.url); assertEquals("My clip", c.title); assertEquals(2000L, c.durationMs)
        assertThrows(IgAudio.LoginRequired::class.java) { IgAudio.parse("ig_a", """{"message":"login_required","status":"fail"}""") }
        assertThrows(IOException::class.java) { IgAudio.parse("ig_a", """{"items":[{"image_versions2":{}}]}""") }
    }
}
