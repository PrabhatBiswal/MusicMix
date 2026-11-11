package com.prabhat.djmix

import com.prabhat.djmix.audio.KeyFinder
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyFinderTest {
    /** A chroma shaped like the textbook profile, started on [tonic]. */
    private fun shaped(profile: DoubleArray, tonic: Int) = DoubleArray(12) { profile[(it - tonic + 12) % 12] }
    private val major = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val minor = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    @Test fun camelotWheel() {
        assertEquals("8B", KeyFinder.key(shaped(major, 0)).camelot)   // C
        assertEquals("9B", KeyFinder.key(shaped(major, 7)).camelot)   // G
        assertEquals("7B", KeyFinder.key(shaped(major, 5)).camelot)   // F
        assertEquals("8A", KeyFinder.key(shaped(minor, 9)).camelot)   // Am
        assertEquals("11A", KeyFinder.key(shaped(minor, 6)).camelot)  // F#m
        assertEquals("F#m", KeyFinder.key(shaped(minor, 6)).name)
    }
}
