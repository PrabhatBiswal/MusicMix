package com.prabhat.djmix.ui.load

import com.prabhat.djmix.library.Library

/** What the Load screen needs from the console behind it (MainActivity implements this): the decks. */
interface LoadHost {
    fun loadedKey(deck: String): String?
    fun onLoad(entry: Library.Entry, deck: String)
    /** Copy a cloud track to the phone and load it. */
    fun onFetch(vid: String, deck: String)
    fun onYouTube(deck: String)
    fun onFiles(deck: String)
    /** BPM the deck plays at, 0 without a grid. */
    fun deckBpm(deck: String): Double
    /** Where a plain tap loads: the deck that is not playing. */
    fun idleDeck(): String
}
