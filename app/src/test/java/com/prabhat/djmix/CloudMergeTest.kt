package com.prabhat.djmix

import com.prabhat.djmix.library.CloudRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudMergeTest {
    @Test
    fun keepsKnownTitlesDropsDeletedAndListsMissing() {
        val known = mapOf("a" to "Alpha", "b" to "Beta")
        val names = listOf("a.json", "a.webm", "c.json", "c.webm", "c.vox.flac", "b.inst.flac", "d.m4a")
        val (kept, missing) = CloudRepository.merge(known, names)
        assertEquals(listOf("a" to "Alpha"), kept) // b has no index any more, stems and audio are not entries
        assertEquals(listOf("c"), missing)
    }
}
